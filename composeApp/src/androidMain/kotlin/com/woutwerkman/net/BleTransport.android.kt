package com.woutwerkman.net

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// Same GATT UUIDs as iOS for cross-platform compatibility
private val BLE_SERVICE_UUID: UUID = UUID.fromString("7C3E0000-C4F1-4D5A-A1E2-000000000000")
private val PEER_INFO_UUID: UUID = UUID.fromString("7C3E0001-C4F1-4D5A-A1E2-000000000000")
private val DATA_WRITE_UUID: UUID = UUID.fromString("7C3E0002-C4F1-4D5A-A1E2-000000000000")
private val DATA_NOTIFY_UUID: UUID = UUID.fromString("7C3E0003-C4F1-4D5A-A1E2-000000000000")

// Standard Client Characteristic Configuration Descriptor UUID
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Android application context required for BLE operations.
 * Must be initialized before any BLE transport is started, e.g. from [android.app.Activity.onCreate].
 */
object BleApplicationContext {
    @Volatile
    var context: Context? = null
}

internal actual suspend fun <T> withBlePlatform(
    peerId: String,
    peerInfoData: ByteArray,
    discoveryChannel: SendChannel<PeerInfo>,
    incomingChannel: SendChannel<Pair<String, ByteArray>>,
    block: suspend CoroutineScope.(sendToPeer: (String, ByteArray) -> Boolean) -> T,
): T {
    val context = BleApplicationContext.context
    val bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter = bluetoothManager?.adapter

    if (context == null || bluetoothManager == null || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
        println("[BLE-$peerId] Bluetooth not available, skipping BLE transport")
        return coroutineScope { block { _, _ -> false } }
    }

    val bleState = AndroidBleState()

    val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
    val advertiseCallback = BleAdvertiseCallback(peerId)

    // Advertising starts from onServiceAdded callback, matching iOS pattern
    val serverCallback = GattServerCallback(peerId, peerInfoData, incomingChannel, bleState) {
        try {
            startAdvertising(advertiser, advertiseCallback)
        } catch (e: SecurityException) {
            println("[BLE-$peerId] Missing BLUETOOTH_ADVERTISE permission: ${e.message}")
        }
    }

    val gattServer = try {
        bluetoothManager.openGattServer(context, serverCallback).also { server ->
            serverCallback.gattServer = server
            setupGattService(server, bleState)
        }
    } catch (e: SecurityException) {
        println("[BLE-$peerId] Missing BLE permissions: ${e.message}")
        return coroutineScope { block { _, _ -> false } }
    }
    bleState.gattServer = gattServer

    val scanner = bluetoothAdapter.bluetoothLeScanner
    val gattCallback = GattClientCallback(peerId, discoveryChannel, incomingChannel, bleState, context)
    val scanCallback = BleScanCallback(peerId, bleState, context, gattCallback)
    try {
        startScanning(scanner, scanCallback)
    } catch (e: SecurityException) {
        println("[BLE-$peerId] Missing BLUETOOTH_SCAN permission: ${e.message}")
    }

    return try {
        coroutineScope {
            block { targetPeerId, data -> bleState.sendToPeer(peerId, targetPeerId, data) }
        }
    } finally {
        withContext(NonCancellable) {
            println("[BLE-$peerId] Stopping BLE transport")
            try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
            bleState.gattClients.values.forEach {
                try { it.disconnect(); it.close() } catch (_: Exception) {}
            }
            try { gattServer.close() } catch (_: Exception) {}
            // Channels are owned by withBleTransport — do not close here
        }
    }
}

// --- Shared BLE State ---

private class AndroidBleState {
    // Central role: peripherals we connected to
    val gattClients = ConcurrentHashMap<String, BluetoothGatt>()   // deviceAddress → gatt
    val peerIdByAddress = ConcurrentHashMap<String, String>()       // deviceAddress → peerId
    val writeCharByAddress = ConcurrentHashMap<String, BluetoothGattCharacteristic>()

    // Peripheral role: centrals subscribed to our DataNotify characteristic
    val subscribedCentrals = CopyOnWriteArrayList<BluetoothDevice>()
    val scannedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile var notifyCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile var gattServer: BluetoothGattServer? = null

    fun sendToPeer(localPeerId: String, targetPeerId: String, data: ByteArray): Boolean {
        val messageBytes = "$localPeerId:${data.decodeToString()}".encodeToByteArray()

        // Central → peripheral: write to their DATA_WRITE characteristic
        val address = peerIdByAddress.entries.find { it.value == targetPeerId }?.key
        val gatt = address?.let { gattClients[it] }
        val writeChar = address?.let { writeCharByAddress[it] }
        if (gatt != null && writeChar != null) {
            return writeCharacteristicCompat(gatt, writeChar, messageBytes)
        }

        // Peripheral → central: notify all subscribed centrals
        val notifyChar = notifyCharacteristic
        val server = gattServer
        if (notifyChar != null && server != null && subscribedCentrals.isNotEmpty()) {
            subscribedCentrals.forEach { device ->
                notifyCharacteristicCompat(server, device, notifyChar, messageBytes)
            }
            return true
        }

        return false
    }
}

// --- API-level-aware wrappers for deprecated BLE methods ---

private fun writeCharacteristicCompat(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    data: ByteArray,
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(
            characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        ) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(characteristic)
    }
}

private fun notifyCharacteristicCompat(
    server: BluetoothGattServer,
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    data: ByteArray,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        server.notifyCharacteristicChanged(device, characteristic, false, data)
    } else {
        @Suppress("DEPRECATION")
        characteristic.value = data
        @Suppress("DEPRECATION")
        server.notifyCharacteristicChanged(device, characteristic, false)
    }
}

private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value)
    } else {
        @Suppress("DEPRECATION")
        descriptor.value = value
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
    }
}

// --- GATT Server (peripheral role) ---

private fun setupGattService(gattServer: BluetoothGattServer, bleState: AndroidBleState) {
    val service = BluetoothGattService(BLE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

    val peerInfoChar = BluetoothGattCharacteristic(
        PEER_INFO_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
    )

    val dataWriteChar = BluetoothGattCharacteristic(
        DATA_WRITE_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    val dataNotifyChar = BluetoothGattCharacteristic(
        DATA_NOTIFY_UUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        0, // No direct read/write permission; CCCD descriptor handles subscription
    )
    val cccd = BluetoothGattDescriptor(
        CCCD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )
    dataNotifyChar.addDescriptor(cccd)
    bleState.notifyCharacteristic = dataNotifyChar

    service.addCharacteristic(peerInfoChar)
    service.addCharacteristic(dataWriteChar)
    service.addCharacteristic(dataNotifyChar)

    gattServer.addService(service)
}

private class GattServerCallback(
    private val peerId: String,
    private val peerInfoData: ByteArray,
    private val incomingChannel: SendChannel<Pair<String, ByteArray>>,
    private val bleState: AndroidBleState,
    private val onServiceReady: () -> Unit,
) : BluetoothGattServerCallback() {

    lateinit var gattServer: BluetoothGattServer

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            println("[BLE-$peerId] GATT service added, starting advertising")
            onServiceReady()
        } else {
            println("[BLE-$peerId] Failed to add GATT service: $status")
        }
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED ->
                println("[BLE-$peerId] Central connected: ${device.address}")
            BluetoothProfile.STATE_DISCONNECTED -> {
                println("[BLE-$peerId] Central disconnected: ${device.address}")
                bleState.subscribedCentrals.remove(device)
            }
        }
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice, requestId: Int, offset: Int,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (characteristic.uuid == PEER_INFO_UUID) {
            val slice = if (offset < peerInfoData.size) {
                peerInfoData.copyOfRange(offset, peerInfoData.size)
            } else {
                ByteArray(0)
            }
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        } else {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
    ) {
        if (characteristic.uuid == DATA_WRITE_UUID && value != null) {
            val message = value.decodeToString()
            val separatorIndex = message.indexOf(':')
            if (separatorIndex > 0) {
                val fromPeerId = message.substring(0, separatorIndex)
                val payload = message.substring(separatorIndex + 1).encodeToByteArray()
                incomingChannel.trySend(fromPeerId to payload)
            }
        }
        if (responseNeeded) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
    ) {
        if (descriptor.uuid == CCCD_UUID) {
            val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            if (enabled) {
                bleState.subscribedCentrals.add(device)
                println("[BLE-$peerId] Central ${device.address} subscribed to notifications")
            } else {
                bleState.subscribedCentrals.remove(device)
                println("[BLE-$peerId] Central ${device.address} unsubscribed from notifications")
            }
        }
        if (responseNeeded) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }
}

// --- LE Advertiser ---

private fun startAdvertising(advertiser: BluetoothLeAdvertiser?, callback: AdvertiseCallback) {
    advertiser ?: return
    val settings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setConnectable(true)
        .setTimeout(0) // advertise indefinitely
        .build()
    val data = AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(BLE_SERVICE_UUID))
        .setIncludeDeviceName(false)
        .build()
    advertiser.startAdvertising(settings, data, callback)
}

private class BleAdvertiseCallback(private val peerId: String) : AdvertiseCallback() {
    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
        println("[BLE-$peerId] Advertising started")
    }
    override fun onStartFailure(errorCode: Int) {
        println("[BLE-$peerId] Advertising failed: $errorCode")
    }
}

// --- LE Scanner ---

private fun startScanning(scanner: BluetoothLeScanner?, callback: ScanCallback) {
    scanner ?: return
    val filter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(BLE_SERVICE_UUID))
        .build()
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .build()
    scanner.startScan(listOf(filter), settings, callback)
}

private class BleScanCallback(
    private val peerId: String,
    private val bleState: AndroidBleState,
    private val context: Context,
    private val gattCallback: BluetoothGattCallback,
) : ScanCallback() {

    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val device = result.device
        val address = device.address
        if (!bleState.scannedAddresses.add(address)) return
        println("[BLE-$peerId] Discovered peripheral: $address, RSSI=${result.rssi}")
        try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            println("[BLE-$peerId] Missing BLUETOOTH_CONNECT permission: ${e.message}")
            bleState.scannedAddresses.remove(address)
        }
    }

    override fun onScanFailed(errorCode: Int) {
        println("[BLE-$peerId] Scan failed: $errorCode")
    }
}

// --- GATT Client (central role) ---

private class GattClientCallback(
    private val peerId: String,
    private val discoveryChannel: SendChannel<PeerInfo>,
    private val incomingChannel: SendChannel<Pair<String, ByteArray>>,
    private val bleState: AndroidBleState,
    private val context: Context,
) : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                println("[BLE-$peerId] Connected to ${gatt.device.address}")
                bleState.gattClients[gatt.device.address] = gatt
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    println("[BLE-$peerId] Missing BLUETOOTH_CONNECT permission: ${e.message}")
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                println("[BLE-$peerId] Disconnected from ${gatt.device.address}")
                bleState.gattClients.remove(gatt.device.address)
                bleState.scannedAddresses.remove(gatt.device.address)
                bleState.peerIdByAddress.remove(gatt.device.address)
                bleState.writeCharByAddress.remove(gatt.device.address)
                try { gatt.close() } catch (_: Exception) {}
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            println("[BLE-$peerId] Service discovery failed on ${gatt.device.address}: $status")
            return
        }
        val service = gatt.getService(BLE_SERVICE_UUID) ?: return
        val peerInfoChar = service.getCharacteristic(PEER_INFO_UUID) ?: return
        try {
            gatt.readCharacteristic(peerInfoChar)
        } catch (e: SecurityException) {
            println("[BLE-$peerId] Missing BLUETOOTH_CONNECT permission: ${e.message}")
        }
    }

    // Overriding the deprecated pre-API-33 version handles all API levels:
    // Android guarantees it is called on all APIs as long as this override is present.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS || characteristic.uuid != PEER_INFO_UUID) return
        handlePeerInfoRead(gatt, characteristic.value ?: return)
    }

    private fun handlePeerInfoRead(gatt: BluetoothGatt, value: ByteArray) {
        val (remotePeerId, remoteAddress, remotePort) = decodePeerInfoFromBle(value) ?: return
        if (remotePeerId == peerId) return

        println("[BLE-$peerId] Read PeerInfo: $remotePeerId at $remoteAddress:$remotePort")
        bleState.peerIdByAddress[gatt.device.address] = remotePeerId

        val service = gatt.getService(BLE_SERVICE_UUID)
        service?.getCharacteristic(DATA_WRITE_UUID)?.let { writeChar ->
            bleState.writeCharByAddress[gatt.device.address] = writeChar
        }

        discoveryChannel.trySend(
            PeerInfo(id = remotePeerId, name = remotePeerId, address = remoteAddress, port = remotePort)
        )

        // Subscribe to notifications (sequential after read, which is the correct BLE pattern)
        service?.getCharacteristic(DATA_NOTIFY_UUID)?.let { notifyChar ->
            try {
                gatt.setCharacteristicNotification(notifyChar, true)
                notifyChar.getDescriptor(CCCD_UUID)?.let { descriptor ->
                    writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            } catch (e: SecurityException) {
                println("[BLE-$peerId] Missing BLUETOOTH_CONNECT permission: ${e.message}")
            }
        }
    }

    // Same pattern: pre-API-33 override handles all API levels.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid != DATA_NOTIFY_UUID) return
        val value = characteristic.value ?: return
        val message = value.decodeToString()
        val separatorIndex = message.indexOf(':')
        if (separatorIndex > 0) {
            val fromPeerId = message.substring(0, separatorIndex)
            val payload = message.substring(separatorIndex + 1).encodeToByteArray()
            incomingChannel.trySend(fromPeerId to payload)
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        println("[BLE-$peerId] Subscribed to notifications on ${gatt.device.address}: status=$status")
    }
}
