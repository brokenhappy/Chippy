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

### Possible solutions (not yet implemented)

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
