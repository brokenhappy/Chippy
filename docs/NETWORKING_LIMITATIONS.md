# Networking Limitations Across Platforms

This document captures known networking limitations discovered through testing,
particularly around iPhone Personal Hotspot connectivity.

## iPhone Personal Hotspot: App-Level Networking Is Blocked

**Tested: 2026-04-02**

When an iPhone creates a Personal Hotspot and another device connects to it,
Apple blocks ALL app-level network traffic between the hotspot phone and
connected clients. This is an OS-level restriction, not a firewall or
configuration issue.

### What was tested

All tests were performed between an iPhone (hotspot host, 172.20.10.1) and a
MacBook (connected client, 172.20.10.x). Every protocol was tested in both
directions.

| Protocol | Direction | Result |
|----------|-----------|--------|
| UDP unicast | Client -> Phone | **Blocked** |
| UDP unicast | Phone -> Client | **Blocked** |
| UDP broadcast (255.255.255.255) | Either direction | **Blocked** |
| UDP multicast (239.255.42.99) | Either direction | **Blocked** |
| TCP connect | Client -> Phone | **Blocked** (connection refused / timeout) |
| TCP connect | Phone -> Client | **Blocked** (connection refused / timeout) |
| ICMP ping | Client -> Phone | **Blocked** |
| mDNS discovery (224.0.0.251:5353) | JVM discovers iOS | **Works** |
| mDNS discovery (224.0.0.251:5353) | iOS discovers JVM | **Does not work** |

### Why mDNS partially works

mDNS appears to work one-way because it is handled by `mDNSResponder`, an
OS-level daemon, not by the app. When JmDNS on the Mac sends a query to
224.0.0.251:5353, the iPhone's `mDNSResponder` (which registered the Bonjour
service) responds. This response comes from the OS networking stack, not from
the app, so it bypasses the hotspot restriction.

The reverse (iOS Bonjour browser discovering JVM services) does not work
reliably. The iOS `NWBrowser` or `NSNetServiceBrowser` sends queries, but the
JVM's JmDNS response may not reach the phone through the hotspot filter.

### Hotspot network details

- Subnet: `172.20.10.0/28` (very small, only 14 usable addresses)
- iPhone (hotspot host): `172.20.10.1`
- Connected clients: `172.20.10.2` through `172.20.10.14`
- The subnet is NAT'd — external internet access works, but local peer traffic does not

### What does NOT fix this

These approaches were all tested and confirmed to not work:

1. **Multicast sockets** (`MulticastSocket` + `joinGroup` on custom group): Apple only
   allows OS-managed multicast (mDNS on 224.0.0.251). App-joined multicast groups
   are silently dropped.

2. **TCP connections**: Neither inbound (client -> phone server socket) nor outbound
   (phone -> client server socket) TCP connections succeed. The connections time out
   or are refused.

3. **Subnet scanning**: Scanning 172.20.10.2-14 with UDP or TCP finds nothing because
   all app-level packets are dropped.

4. **Broadcast UDP** to 255.255.255.255: Blocked like everything else.

### Implemented solution: BLE Discovery + Data Fallback

**Implemented: 2026-04-02**

Bluetooth Low Energy (BLE) is used as a supplementary peer discovery mechanism and
data transport fallback on iOS. WiFi/UDP is always preferred when available.

#### How it works

1. **BLE Discovery**: Every iOS device runs as both BLE peripheral (advertising) and
   central (scanning) simultaneously. Advertising packets carry only the service UUID
   (~31 bytes). The actual peer info (peerId, IP address, UDP port) is exchanged via
   GATT — the central connects, reads the PeerInfo characteristic, then feeds the
   discovered address into the existing UDP handshake flow.

2. **UDP preferred**: After BLE discovers a peer's IP+port, the existing UDP HELLO/ACK
   handshake is attempted. If UDP succeeds, all data flows over UDP (faster, higher
   throughput).

3. **BLE data fallback**: If the UDP handshake times out (e.g., on a hotspot where all
   app-level UDP is blocked), BLE carries data instead. Messages are sent via GATT
   characteristics: DataWrite (central → peripheral) and DataNotify (peripheral →
   central).

#### BLE throughput

- Practical throughput: ~100-200 KB/s on BLE 5.0
- Current game traffic: ~5 KB/s typical (dominated by periodic state sync)
- BLE is sufficient for current needs; WiFi is always preferred for future scalability

#### GATT Service

- Service UUID: `7C3E0000-C4F1-4D5A-A1E2-000000000000`
- PeerInfo characteristic (Read): `7C3E0001-...` — compact binary-encoded peer info
- DataWrite characteristic (Write Without Response): `7C3E0002-...`
- DataNotify characteristic (Notify): `7C3E0003-...`

#### PeerInfo binary encoding

```
Byte 0:       Flags (bit 0: 0=IPv4, 1=IPv6)
Bytes 1-4:    IPv4 address (4 bytes) OR Bytes 1-16: IPv6 address (16 bytes)
Next 2:       UDP port (big-endian)
Remaining:    peerId (UTF-8, variable length)
```

IPv4 total: ~25 bytes. IPv6 total: ~37 bytes. Both fit in negotiated BLE MTU (185+
on iOS).

#### Permission requirements

iOS apps using BLE require `NSBluetoothAlwaysUsageDescription` in Info.plist. The
first BLE operation triggers a system permission dialog.

#### Testing

A Mac-side Swift BLE test helper (`bleTestHelper/main.swift`) acts as a BLE
peripheral + central for testing BLE discovery with iOS devices. Run via:
```bash
./gradlew :connectivityTest:testConnectivity -Pskip-platforms=android-simulator,android-real-device,ios-simulator
```

#### Limitations

- BLE is not available on iOS simulators (real devices required)
- BLE throughput is limited (~100-200 KB/s) — adequate for current game, but WiFi
  should always be preferred for data-heavy applications
- First BLE use triggers iOS permission dialog, which may delay test timeouts

### Other possible solutions (not yet implemented)

1. **Multipeer Connectivity Framework** (iOS-only API): Uses Bluetooth + WiFi Direct
   to communicate between nearby devices without going through the IP network.
   Requires platform-specific implementation (no Kotlin Multiplatform abstraction).

2. **Network.framework** (`NWConnection` / `NWListener`): Apple's modern networking
   API that may have special privileges on hotspot networks. Worth investigating
   whether it bypasses the restrictions that BSD sockets hit.

3. **External relay server**: Route traffic through an internet-accessible server.
   Both devices can reach the internet through the hotspot, so a WebSocket relay
   or TURN server would work. Adds latency and requires infrastructure.

4. **WiFi Direct / WiFi Aware** (Android): For Android-to-Android communication
   without a shared network. Not applicable to iOS.

## Other Platform Notes

### Android Emulator Networking

- Android emulators use `10.0.2.x` addresses and cannot directly reach the host's
  network interfaces.
- `adb reverse tcp:PORT tcp:PORT` is required to forward traffic from the emulator
  to the host.
- The emulator's `10.0.2.2` maps to the host's loopback.

### iOS Simulator Networking

- iOS simulators share the host Mac's network stack and can communicate with other
  processes on the same machine without issues.
- Bonjour/mDNS works normally in the simulator.

### Build Caching Pitfall: Xcode DerivedData

The connectivity test runner (`ConnectivityTestRunner.kt`, `buildIosDeviceApp`)
checks `~/Library/Developer/Xcode/DerivedData/iosConnectivityTest-*/Build/Products/`
for a cached `.app` bundle. If found, it skips the Xcode build. This can cause
**stale binaries** to be deployed to a real device if code has changed since the
last build.

**Workaround**: Delete the DerivedData cache before running device tests:
```bash
rm -rf ~/Library/Developer/Xcode/DerivedData/iosConnectivityTest-*
```
