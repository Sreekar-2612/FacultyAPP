package com.facultybeacon.beacon

/**
 * Everything about one beacon session that gets reported to Supabase.
 *
 * @param uuid         the beacon UUID broadcast and reported as `test_message`:
 *                     32 lowercase hex characters with no dashes. Either the configured
 *                     [com.facultybeacon.network.SupabaseConfig.MESSAGE_UUID] or a fresh
 *                     random 32-hex value per session (SecureRandom-backed).
 * @param bluetoothMac Bluetooth MAC reported to Supabase. Android does not expose the real
 *                     hardware MAC since Android 6, so this is either the adapter's address
 *                     (when it is not the privacy placeholder) or a persistent random
 *                     pseudo-MAC stored on first run. See [BeaconSessionManager].
 * @param deviceName   Bluetooth adapter name (or device model as fallback)
 * @param deviceSecret persistent per-device secret (random, stored on first run)
 */
data class BeaconSession(
    val uuid: String,
    val bluetoothMac: String,
    val deviceName: String,
    val deviceSecret: String
)