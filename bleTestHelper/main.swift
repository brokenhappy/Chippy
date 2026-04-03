import CoreBluetooth
import Foundation

// Must match the UUIDs in BleTransport.ios.kt
let serviceUUID = CBUUID(string: "7C3E0000-C4F1-4D5A-A1E2-000000000000")
let peerInfoUUID = CBUUID(string: "7C3E0001-C4F1-4D5A-A1E2-000000000000")
let dataWriteUUID = CBUUID(string: "7C3E0002-C4F1-4D5A-A1E2-000000000000")
let dataNotifyUUID = CBUUID(string: "7C3E0003-C4F1-4D5A-A1E2-000000000000")

// Parse arguments: instanceId [--control-host HOST --control-port PORT]
let instanceId: String
var controlHost: String? = nil
var controlPort: Int32 = 0

do {
    let args = CommandLine.arguments
    instanceId = args.count > 1 ? args[1] : "mac-ble-helper"

    for (index, arg) in args.enumerated() {
        if arg == "--control-host" && index + 1 < args.count {
            controlHost = args[index + 1]
        }
        if arg == "--control-port" && index + 1 < args.count {
            controlPort = Int32(args[index + 1]) ?? 0
        }
    }
}

// --- TCP Control Channel ---

class ControlChannel {
    private let fd: Int32

    init?(host: String, port: Int32) {
        fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else {
            log("Failed to create control socket")
            return nil
        }

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(port).bigEndian
        inet_pton(AF_INET, host, &addr.sin_addr)

        let result = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                connect(fd, sockPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        guard result == 0 else {
            log("Failed to connect to control server \(host):\(port) (errno=\(errno))")
            Darwin.close(fd)
            return nil
        }

        log("Connected to control server \(host):\(port)")
    }

    func sendLine(_ line: String) {
        let data = "\(line)\n".data(using: .utf8)!
        data.withUnsafeBytes { ptr in
            _ = Darwin.write(fd, ptr.baseAddress!, data.count)
        }
    }

    func readLine() -> String? {
        var buffer = ""
        var byte: UInt8 = 0
        while true {
            let n = Darwin.read(fd, &byte, 1)
            if n <= 0 { return nil }
            if byte == UInt8(ascii: "\n") { break }
            buffer.append(Character(UnicodeScalar(byte)))
        }
        return buffer.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func shutdown() {
        Darwin.close(fd)
    }
}

// --- Logging ---

func log(_ msg: String) {
    print("[BLE-Helper] \(msg)")
    fflush(stdout)
}

// --- PeerInfo binary encoding (matches BlePeerInfo.kt) ---

func getLocalIPv4Address() -> String {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return "0.0.0.0" }
    defer { freeifaddrs(ifaddr) }

    for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
        let addr = ptr.pointee
        guard addr.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
        let name = String(cString: addr.ifa_name)
        guard name == "en0" || name == "en1" else { continue }
        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(addr.ifa_addr, socklen_t(addr.ifa_addr.pointee.sa_len),
                       &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
            let ip = String(cString: hostname)
            if !ip.hasPrefix("127.") && !ip.hasPrefix("169.254.") {
                return ip
            }
        }
    }
    return "0.0.0.0"
}

func encodePeerInfo(peerId: String, address: String, port: UInt16) -> Data {
    let ipParts = address.split(separator: ".").compactMap { UInt8($0) }
    guard ipParts.count == 4 else { return Data() }
    var data = Data()
    data.append(0x00) // flags: IPv4
    data.append(contentsOf: ipParts)
    data.append(UInt8(port >> 8))
    data.append(UInt8(port & 0xFF))
    data.append(contentsOf: peerId.utf8)
    return data
}

func decodePeerInfo(data: Data) -> (peerId: String, address: String, port: UInt16)? {
    guard data.count >= 8 else { return nil }
    let isIPv6 = (data[0] & 0x01) != 0
    let ipSize = isIPv6 ? 16 : 4
    guard data.count >= 1 + ipSize + 2 + 1 else { return nil }
    var offset = 1
    let address: String
    if isIPv6 {
        let bytes = Array(data[offset..<offset+16])
        var groups = [String]()
        for i in stride(from: 0, to: 16, by: 2) {
            let val16 = (UInt16(bytes[i]) << 8) | UInt16(bytes[i+1])
            groups.append(String(val16, radix: 16))
        }
        address = groups.joined(separator: ":")
        offset += 16
    } else {
        address = "\(data[offset]).\(data[offset+1]).\(data[offset+2]).\(data[offset+3])"
        offset += 4
    }
    let port = (UInt16(data[offset]) << 8) | UInt16(data[offset+1])
    offset += 2
    let peerIdData = data[offset...]
    guard let peerId = String(data: peerIdData, encoding: .utf8) else { return nil }
    return (peerId, address, port)
}

// --- BLE Helper ---

class BleTestHelper: NSObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate {
    var centralManager: CBCentralManager!
    var peripheralManager: CBPeripheralManager!
    var discoveredPeripherals = Set<UUID>()
    var connectingPeripherals = [CBPeripheral]() // strong reference

    let localAddress = getLocalIPv4Address()
    let localPort: UInt16 = 0
    var peerInfoData: Data!
    var discoveredPeerIds = Set<String>()
    var succeeded = false
    var control: ControlChannel?
    var waitingForStart = true

    func start(control: ControlChannel?) {
        self.control = control
        peerInfoData = encodePeerInfo(peerId: instanceId, address: localAddress, port: localPort)
        log("Starting BLE test helper: \(instanceId)")
        log("Local address: \(localAddress)")

        if #available(macOS 10.15, *) {
            let authStatus = CBManager.authorization
            log("Bluetooth authorization: \(authStatus.rawValue)")
        }

        centralManager = CBCentralManager(delegate: self, queue: nil)
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
        log("Managers created, waiting for state callbacks...")

        if let ctrl = control {
            // TCP control channel mode: identify, send READY, wait for START
            ctrl.sendLine("HELLO:\(instanceId)")
            ctrl.sendLine("READY")
            log("Sent READY to coordinator")

            DispatchQueue.global().async {
                if let line = ctrl.readLine(), line == "START" {
                    log("Received START from coordinator")
                    DispatchQueue.main.async {
                        self.waitingForStart = false
                        // Scanning/advertising was already started by Bluetooth power-on callbacks,
                        // so nothing extra to do here — the gate only gates reporting results.
                    }
                }
            }
        } else {
            waitingForStart = false
            // No control channel: timeout fallback
            DispatchQueue.main.asyncAfter(deadline: .now() + 60) {
                if !self.succeeded {
                    log("TIMEOUT: No peers discovered within 60s")
                    exit(1)
                }
            }
        }
    }

    private func reportSuccess(peerId: String, via: String) {
        guard !succeeded else { return }
        succeeded = true
        log("SUCCESS: Discovered peer '\(peerId)' via BLE \(via)")

        if let ctrl = control {
            ctrl.sendLine("FOUND:mac-ble-helper")
            ctrl.sendLine("DONE")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                exit(0)
            }
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                exit(0)
            }
        }
    }

    // --- CBCentralManagerDelegate ---

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        log("Central state: \(central.state.rawValue)")
        if central.state == .poweredOn {
            log("Scanning for Chippy BLE peers...")
            central.scanForPeripherals(withServices: [serviceUUID], options: nil)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        guard !discoveredPeripherals.contains(id) else { return }
        discoveredPeripherals.insert(id)
        log("Discovered peripheral: \(id), RSSI=\(RSSI)")
        connectingPeripherals.append(peripheral)
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        log("Connected to peripheral: \(peripheral.identifier)")
        peripheral.delegate = self
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        log("Failed to connect: \(error?.localizedDescription ?? "unknown")")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            log("Retrying connection to \(peripheral.identifier)...")
            central.connect(peripheral, options: nil)
        }
    }

    // --- CBPeripheralDelegate ---

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            log("Service discovery error: \(error!.localizedDescription)")
            return
        }
        for service in peripheral.services ?? [] {
            if service.uuid == serviceUUID {
                peripheral.discoverCharacteristics([peerInfoUUID, dataWriteUUID, dataNotifyUUID], for: service)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            log("Characteristic discovery error: \(error!.localizedDescription)")
            return
        }
        for char in service.characteristics ?? [] {
            if char.uuid == peerInfoUUID {
                peripheral.readValue(for: char)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil else {
            log("Read error: \(error!.localizedDescription) — will disconnect and retry")
            centralManager.cancelPeripheralConnection(peripheral)
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                log("Reconnecting to \(peripheral.identifier)...")
                self.centralManager.connect(peripheral, options: nil)
            }
            return
        }
        guard let data = characteristic.value else { return }

        if characteristic.uuid == peerInfoUUID {
            if let info = decodePeerInfo(data: data) {
                log("Read PeerInfo: peerId=\(info.peerId), address=\(info.address), port=\(info.port)")
                if !waitingForStart {
                    reportSuccess(peerId: info.peerId, via: "PeerInfo read")
                } else {
                    discoveredPeerIds.insert(info.peerId)
                    log("Peer found but waiting for START gate")
                }
            }
        }
    }

    // --- CBPeripheralManagerDelegate ---

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        log("Peripheral state: \(peripheral.state.rawValue)")
        if peripheral.state == .poweredOn {
            let peerInfoChar = CBMutableCharacteristic(
                type: peerInfoUUID,
                properties: .read,
                value: peerInfoData,
                permissions: .readable
            )
            let dataWriteChar = CBMutableCharacteristic(
                type: dataWriteUUID,
                properties: .writeWithoutResponse,
                value: nil,
                permissions: .writeable
            )
            let dataNotifyChar = CBMutableCharacteristic(
                type: dataNotifyUUID,
                properties: .notify,
                value: nil,
                permissions: .readable
            )
            let service = CBMutableService(type: serviceUUID, primary: true)
            service.characteristics = [peerInfoChar, dataWriteChar, dataNotifyChar]
            peripheral.add(service)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            log("Failed to add service: \(error.localizedDescription)")
            return
        }
        log("Service added, starting advertisement")
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            log("Failed to advertise: \(error.localizedDescription)")
        } else {
            log("Advertising started")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            if let data = req.value, req.characteristic.uuid == dataWriteUUID {
                if let message = String(data: data, encoding: .utf8) {
                    log("Received data write: \(message.prefix(50))")
                    if let colonIndex = message.firstIndex(of: ":") {
                        let fromPeerId = String(message[message.startIndex..<colonIndex])
                        if !fromPeerId.isEmpty && !discoveredPeerIds.contains(fromPeerId) {
                            discoveredPeerIds.insert(fromPeerId)
                            if !waitingForStart {
                                reportSuccess(peerId: fromPeerId, via: "data write")
                            } else {
                                log("Peer found via data write but waiting for START gate")
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Main ---

let control: ControlChannel?
if let host = controlHost, controlPort > 0 {
    control = ControlChannel(host: host, port: controlPort)
    if control == nil {
        log("ERROR: Failed to connect to control server")
        exit(1)
    }
} else {
    control = nil
    log("No control channel — running in standalone mode")
}

let helper = BleTestHelper()
helper.start(control: control)
RunLoop.main.run()
