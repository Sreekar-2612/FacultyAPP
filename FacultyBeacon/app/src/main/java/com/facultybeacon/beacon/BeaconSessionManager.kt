package com.facultybeacon.beacon

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * Creates new beacon sessions and manages the persistent per-device identifiers.
 *
 * The ESP32 reported its real Bluetooth MAC and a fixed device secret. Android does not
 * expose the real MAC (privacy, since Android 6) so this class substitutes a persistent
 * random pseudo-MAC, and generates a persistent random device secret, both stored in
 * SharedPreferences so the Supabase rows stay stable per device.
 */
class BeaconSessionManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    /** Generates a fresh 128-bit UUID plus the device's stable identifiers. */
    fun newSession(): BeaconSession {
        val uuid = UUID.randomUUID() // backed by SecureRandom
        val mac = resolveBluetoothMac()
        val secret = getOrCreateDeviceSecret()
        val name = resolveDeviceName()
        return BeaconSession(uuid, mac, name, secret)
    }

    /**
     * Returns the real adapter address when the platform exposes one (pre-Android 6),
     * otherwise a persistent random MAC generated on first run.
     */
    private fun resolveBluetoothMac(): String {
        val realAddress = runCatching {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.address
        }.getOrNull()

        if (!realAddress.isNullOrBlank() && !PRIVACY_PLACEHOLDER_MAC.equals(realAddress, ignoreCase = true)) {
            return realAddress
        }

        prefs.getString(KEY_PSEUDO_MAC, null)?.let { return it }

        val bytes = ByteArray(6).also { secureRandom.nextBytes(it) }
        val generated = bytes.joinToString(":") { byte ->
            String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
        }
        prefs.edit().putString(KEY_PSEUDO_MAC, generated).apply()
        return generated
    }

    /** Bluetooth adapter name, falling back to the device model. */
    private fun resolveDeviceName(): String {
        val adapterName = runCatching {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.name
        }.getOrNull()
        return adapterName?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    /** Persistent random device secret (32 hex chars), created on first run. */
    private fun getOrCreateDeviceSecret(): String {
        prefs.getString(KEY_DEVICE_SECRET, null)?.let { return it }
        val bytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val secret = bytes.joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
        }
        prefs.edit().putString(KEY_DEVICE_SECRET, secret).apply()
        return secret
    }

    private companion object {
        const val PREFS_NAME = "faculty_beacon_prefs"
        const val KEY_PSEUDO_MAC = "pseudo_mac"
        const val KEY_DEVICE_SECRET = "device_secret"

        // BluetoothAdapter.getAddress() returns this fixed value on Android 6+.
        const val PRIVACY_PLACEHOLDER_MAC = "02:00:00:00:00:00"
    }
}