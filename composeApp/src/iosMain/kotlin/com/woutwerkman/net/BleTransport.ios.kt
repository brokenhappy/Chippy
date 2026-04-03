package com.woutwerkman.net

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import kotlin.experimental.ExperimentalObjCRefinement

// GATT Service and Characteristic UUIDs
internal val BLE_SERVICE_UUID = CBUUID.UUIDWithString("7C3E0000-C4F1-4D5A-A1E2-000000000000")
private val PEER_INFO_UUID = CBUUID.UUIDWithString("7C3E0001-C4F1-4D5A-A1E2-000000000000")
private val DATA_WRITE_UUID = CBUUID.UUIDWithString("7C3E0002-C4F1-4D5A-A1E2-000000000000")
private val DATA_NOTIFY_UUID = CBUUID.UUIDWithString("7C3E0003-C4F1-4D5A-A1E2-000000000000")

/**
 * Represents a BLE connection providing discovery and data transport.
 */
internal class BleConnection(
    /** Peers discovered via BLE (peerId, address, port). */
    val discoveredPeers: ReceiveChannel<PeerInfo>,
    /** Data received from BLE peers (peerId to payload). */
    val incoming: ReceiveChannel<Pair<String, ByteArray>>,
    /** Send data to a specific peer over BLE. Returns true if sent. */
    val sendToPeer: (peerId: String, data: ByteArray) -> Boolean,
)

/**
 * Runs BLE transport (peripheral + central) for the duration of [block].
 * Structured concurrency: everything is cleaned up when block completes or is cancelled.
 */
internal suspend fun <T> withBleTransport(
    peerId: String,
    localAddress: String,
    localPort: Int,
    block: suspend CoroutineScope.(BleConnection) -> T,
): T = coroutineScope {
    val discoveryChannel = Channel<PeerInfo>(Channel.BUFFERED)
    val incomingDataChannel = Channel<Pair<String, ByteArray>>(Channel.BUFFERED)
    val bleQueue = dispatch_queue_create("com.woutwerkman.ble", null)

    val peerInfoData = encodePeerInfoForBle(peerId, localAddress, localPort)

    // Shared mutable state accessed only from bleQueue
    val bleState = BleSharedState(peerId)

    // Create delegates (order matters: peripheralReadDelegate is used by centralDelegate)
    val peripheralReadDelegate = PeripheralReadDelegate(
        peerId, discoveryChannel, incomingDataChannel, bleState,
    )
    val peripheralDelegate = PeripheralManagerDelegate(
        peerId, peerInfoData, incomingDataChannel, bleState,
    )
    val centralDelegate = CentralManagerDelegate(
        peerId, bleState, peripheralReadDelegate,
    )

    val peripheralManager = CBPeripheralManager(delegate = peripheralDelegate, queue = bleQueue)
    bleState.peripheralManager = peripheralManager

    val centralManager = CBCentralManager(delegate = centralDelegate, queue = bleQueue)

    val sendToPeer: (String, ByteArray) -> Boolean = { targetPeerId, data ->
        bleState.sendToPeer(peerId, targetPeerId, data)
    }

    val connection = BleConnection(
        discoveredPeers = discoveryChannel,
        incoming = incomingDataChannel,
        sendToPeer = sendToPeer,
    )

    try {
        coroutineScope { block(connection) }
    } finally {
        withContext(NonCancellable) {
            NSLog("[BLE-$peerId] Stopping BLE transport")
            peripheralManager.stopAdvertising()
            centralManager.stopScan()
            for (peripheral in bleState.connectingPeripherals) {
                centralManager.cancelPeripheralConnection(peripheral)
            }
            discoveryChannel.close()
            incomingDataChannel.close()
        }
    }
}

/**
 * Shared mutable state for BLE operations. Accessed from the BLE dispatch queue.
 */
private class BleSharedState(val localPeerId: String) {
    val notifySubscribers = mutableListOf<CBCentral>()
    var notifyCharacteristic: CBMutableCharacteristic? = null
    var peripheralManager: CBPeripheralManager? = null
    val peerPeripherals = mutableMapOf<String, CBPeripheral>()
    val peerWriteCharacteristics = mutableMapOf<String, CBCharacteristic>()
    val discoveredPeripheralIds = mutableSetOf<String>()
    val connectingPeripherals = mutableSetOf<CBPeripheral>()

    fun sendToPeer(localPeerId: String, targetPeerId: String, data: ByteArray): Boolean {
        // Try writing to the peer's peripheral (central → peripheral write)
        val peripheral = peerPeripherals[targetPeerId]
        val writeChar = peerWriteCharacteristics[targetPeerId]
        if (peripheral != null && writeChar != null) {
            val message = "$localPeerId:${data.decodeToString()}"
            peripheral.writeValue(
                message.encodeToByteArray().toNSData(),
                forCharacteristic = writeChar,
                type = CBCharacteristicWriteWithoutResponse,
            )
            return true
        }
        // Try notifying via peripheral manager (peripheral → central notify)
        val notifyChar = notifyCharacteristic
        if (notifyChar != null && notifySubscribers.isNotEmpty()) {
            val message = "$localPeerId:${data.decodeToString()}"
            peripheralManager?.updateValue(
                message.encodeToByteArray().toNSData(),
                forCharacteristic = notifyChar,
                onSubscribedCentrals = null,
            )
            return true
        }
        return false
    }
}

// --- Peripheral Manager Delegate ---

@OptIn(ExperimentalObjCRefinement::class)
private class PeripheralManagerDelegate(
    private val peerId: String,
    private val peerInfoData: ByteArray,
    private val incomingDataChannel: Channel<Pair<String, ByteArray>>,
    private val state: BleSharedState,
) : NSObject(), CBPeripheralManagerDelegateProtocol {

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        NSLog("[BLE-$peerId] Peripheral state: ${peripheral.state}")
        if (peripheral.state == CBPeripheralManagerStatePoweredOn) {
            val peerInfoChar = CBMutableCharacteristic(
                type = PEER_INFO_UUID,
                properties = CBCharacteristicPropertyRead,
                value = peerInfoData.toNSData(),
                permissions = CBAttributePermissionsReadable,
            )
            val dataWriteChar = CBMutableCharacteristic(
                type = DATA_WRITE_UUID,
                properties = CBCharacteristicPropertyWriteWithoutResponse,
                value = null,
                permissions = CBAttributePermissionsWriteable,
            )
            val dataNotifyChar = CBMutableCharacteristic(
                type = DATA_NOTIFY_UUID,
                properties = CBCharacteristicPropertyNotify,
                value = null,
                permissions = CBAttributePermissionsReadable,
            )
            state.notifyCharacteristic = dataNotifyChar

            val service = CBMutableService(type = BLE_SERVICE_UUID, primary = true)
            service.setCharacteristics(listOf(peerInfoChar, dataWriteChar, dataNotifyChar))
            peripheral.addService(service)
        }
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didAddService: CBService,
        error: NSError?,
    ) {
        if (error != null) {
            NSLog("[BLE-$peerId] Failed to add service: ${error.localizedDescription}")
            return
        }
        NSLog("[BLE-$peerId] Service added, starting advertisement")
        peripheral.startAdvertising(
            mapOf(CBAdvertisementDataServiceUUIDsKey to listOf(BLE_SERVICE_UUID))
        )
    }

    override fun peripheralManagerDidStartAdvertising(
        peripheral: CBPeripheralManager,
        error: NSError?,
    ) {
        if (error != null) {
            NSLog("[BLE-$peerId] Failed to start advertising: ${error.localizedDescription}")
        } else {
            NSLog("[BLE-$peerId] Advertising started")
        }
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveWriteRequests: List<*>,
    ) {
        for (request in didReceiveWriteRequests) {
            val req = request as? CBATTRequest ?: continue
            val data = req.value?.toByteArray() ?: continue
            if (req.characteristic.UUID.isEqual(DATA_WRITE_UUID)) {
                val message = data.decodeToString()
                val separatorIndex = message.indexOf(':')
                if (separatorIndex > 0) {
                    val fromPeerId = message.substring(0, separatorIndex)
                    val payload = message.substring(separatorIndex + 1).encodeToByteArray()
                    incomingDataChannel.trySend(fromPeerId to payload)
                }
            }
        }
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeToCharacteristic: CBCharacteristic,
    ) {
        if (didSubscribeToCharacteristic.UUID.isEqual(DATA_NOTIFY_UUID)) {
            NSLog("[BLE-$peerId] Central subscribed to DataNotify")
            state.notifySubscribers.add(central)
        }
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFromCharacteristic: CBCharacteristic,
    ) {
        if (didUnsubscribeFromCharacteristic.UUID.isEqual(DATA_NOTIFY_UUID)) {
            NSLog("[BLE-$peerId] Central unsubscribed from DataNotify")
            state.notifySubscribers.remove(central)
        }
    }
}

// --- Central Manager Delegate ---

@OptIn(ExperimentalObjCRefinement::class)
private class CentralManagerDelegate(
    private val peerId: String,
    private val state: BleSharedState,
    private val peripheralReadDelegate: CBPeripheralDelegateProtocol,
) : NSObject(), CBCentralManagerDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        NSLog("[BLE-$peerId] Central state: ${central.state}")
        if (central.state == CBCentralManagerStatePoweredOn) {
            NSLog("[BLE-$peerId] Starting scan for service $BLE_SERVICE_UUID")
            central.scanForPeripheralsWithServices(listOf(BLE_SERVICE_UUID), options = null)
        }
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val peripheralId = didDiscoverPeripheral.identifier.UUIDString
        if (peripheralId in state.discoveredPeripheralIds) return
        state.discoveredPeripheralIds.add(peripheralId)

        NSLog("[BLE-$peerId] Discovered peripheral: $peripheralId, RSSI=$RSSI")
        state.connectingPeripherals.add(didDiscoverPeripheral)
        central.connectPeripheral(didDiscoverPeripheral, options = null)
    }

    override fun centralManager(
        central: CBCentralManager,
        didConnectPeripheral: CBPeripheral,
    ) {
        NSLog("[BLE-$peerId] Connected to peripheral: ${didConnectPeripheral.identifier.UUIDString}")
        didConnectPeripheral.delegate = peripheralReadDelegate
        didConnectPeripheral.discoverServices(listOf(BLE_SERVICE_UUID))
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        NSLog("[BLE-$peerId] Failed to connect: ${error?.localizedDescription}")
        state.connectingPeripherals.remove(didFailToConnectPeripheral)
        state.discoveredPeripheralIds.remove(didFailToConnectPeripheral.identifier.UUIDString)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        NSLog("[BLE-$peerId] Disconnected from peripheral: ${didDisconnectPeripheral.identifier.UUIDString}")
        state.connectingPeripherals.remove(didDisconnectPeripheral)
        val disconnectedPeerId = state.peerPeripherals.entries
            .find { it.value == didDisconnectPeripheral }?.key
        if (disconnectedPeerId != null) {
            state.peerPeripherals.remove(disconnectedPeerId)
            state.peerWriteCharacteristics.remove(disconnectedPeerId)
        }
    }
}

// --- Peripheral Read Delegate (for reading characteristics from discovered peripherals) ---

@OptIn(ExperimentalObjCRefinement::class)
private class PeripheralReadDelegate(
    private val peerId: String,
    private val discoveryChannel: Channel<PeerInfo>,
    private val incomingDataChannel: Channel<Pair<String, ByteArray>>,
    private val state: BleSharedState,
) : NSObject(), CBPeripheralDelegateProtocol {

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        if (didDiscoverServices != null) {
            NSLog("[BLE-$peerId] Service discovery error: ${didDiscoverServices.localizedDescription}")
            return
        }
        for (service in peripheral.services ?: emptyList<CBService>()) {
            service as CBService
            if (service.UUID.isEqual(BLE_SERVICE_UUID)) {
                peripheral.discoverCharacteristics(
                    listOf(PEER_INFO_UUID, DATA_WRITE_UUID, DATA_NOTIFY_UUID),
                    forService = service,
                )
            }
        }
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) {
        if (error != null) {
            NSLog("[BLE-$peerId] Characteristic discovery error: ${error.localizedDescription}")
            return
        }
        for (characteristic in didDiscoverCharacteristicsForService.characteristics ?: emptyList<CBCharacteristic>()) {
            characteristic as CBCharacteristic
            when {
                characteristic.UUID.isEqual(PEER_INFO_UUID) -> {
                    peripheral.readValueForCharacteristic(characteristic)
                }
                characteristic.UUID.isEqual(DATA_NOTIFY_UUID) -> {
                    peripheral.setNotifyValue(true, forCharacteristic = characteristic)
                }
            }
        }
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        if (error != null) {
            NSLog("[BLE-$peerId] Read error: ${error.localizedDescription}")
            return
        }
        val data = didUpdateValueForCharacteristic.value?.toByteArray() ?: return

        when {
            didUpdateValueForCharacteristic.UUID.isEqual(PEER_INFO_UUID) -> {
                val decoded = decodePeerInfoFromBle(data)
                if (decoded != null) {
                    val (remotePeerId, remoteAddress, remotePort) = decoded
                    if (remotePeerId == peerId) return
                    NSLog("[BLE-$peerId] Read PeerInfo: $remotePeerId at $remoteAddress:$remotePort")

                    state.peerPeripherals[remotePeerId] = peripheral
                    val service = peripheral.services
                        ?.filterIsInstance<CBService>()
                        ?.find { it.UUID.isEqual(BLE_SERVICE_UUID) }
                    val writeChar = service?.characteristics
                        ?.filterIsInstance<CBCharacteristic>()
                        ?.find { it.UUID.isEqual(DATA_WRITE_UUID) }
                    if (writeChar != null) {
                        state.peerWriteCharacteristics[remotePeerId] = writeChar
                    }

                    discoveryChannel.trySend(
                        PeerInfo(
                            id = remotePeerId,
                            name = remotePeerId,
                            address = remoteAddress,
                            port = remotePort,
                        )
                    )
                }
            }
            didUpdateValueForCharacteristic.UUID.isEqual(DATA_NOTIFY_UUID) -> {
                val message = data.decodeToString()
                val separatorIndex = message.indexOf(':')
                if (separatorIndex > 0) {
                    val fromPeerId = message.substring(0, separatorIndex)
                    val payload = message.substring(separatorIndex + 1).encodeToByteArray()
                    incomingDataChannel.trySend(fromPeerId to payload)
                }
            }
        }
    }
}

// --- NSData <-> ByteArray helpers ---

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val result = ByteArray(length)
    result.usePinned { pinned ->
        this.getBytes(pinned.addressOf(0), length.toULong())
    }
    return result
}
