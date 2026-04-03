package com.woutwerkman.net

/**
 * Platform identifiers for connectivity testing.
 */
enum class TestPlatform {
    JVM,
    ANDROID_SIMULATOR,
    ANDROID_REAL_DEVICE,
    IOS_SIMULATOR,
    IOS_REAL_DEVICE,
    MAC_BLE_HELPER;

    fun toPlatformString(): String = name.lowercase().replace('_', '-')

    companion object {
        fun fromString(s: String): TestPlatform? = when (s.lowercase().trim()) {
            "jvm" -> JVM
            "android-simulator" -> ANDROID_SIMULATOR
            "android-real-device" -> ANDROID_REAL_DEVICE
            "ios-simulator" -> IOS_SIMULATOR
            "ios-real-device" -> IOS_REAL_DEVICE
            "mac-ble-helper" -> MAC_BLE_HELPER
            else -> null
        }

        fun fromPeerId(peerId: String): TestPlatform? = when {
            peerId.startsWith("jvm-") -> JVM
            peerId.startsWith("android-sim-") -> ANDROID_SIMULATOR
            peerId.startsWith("android-device-") -> ANDROID_REAL_DEVICE
            peerId.startsWith("android-") -> ANDROID_SIMULATOR
            peerId.startsWith("ios-sim") -> IOS_SIMULATOR
            peerId.startsWith("ios-device") -> IOS_REAL_DEVICE
            peerId.startsWith("ios-") -> IOS_REAL_DEVICE
            peerId.startsWith("mac-ble-") -> MAC_BLE_HELPER
            else -> null
        }
    }
}
