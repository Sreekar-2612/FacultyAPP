package com.facultybeacon.beacon

/**
 * Pure helpers for building the BLE advertisement payload, kept out of the Android
 * framework classes so they can be unit-tested on the JVM.
 *
 * The payload reproduces the ESP32's manufacturer-specific data layout:
 *
 *     2 bytes manufacturer identifier (0xFFFF -> bytes FF FF)
 *   + 16 bytes random UUID
 */
object BlePayload {

    /** Manufacturer identifier used by the ESP32 (manuData[0] = 0xFF, manuData[1] = 0xFF). */
    const val MANUFACTURER_ID = 0xFFFF

    private const val HEX_CHARS = "0123456789abcdef"

    /** Serializes a 32-hex-char UUID (no dashes) to its canonical 16 bytes, big-endian. */
    fun uuidToBytes(uuid: String): ByteArray {
        require(uuid.length == 32) { "UUID must be exactly 32 hex characters" }
        require(uuid.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "UUID must be hexadecimal"
        }
        return ByteArray(16) { i -> uuid.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Reconstructs a 32-hex-char UUID from the 16 bytes produced by [uuidToBytes] (for tests/debug). */
    fun bytesToUuid(bytes: ByteArray): String {
        require(bytes.size == 16) { "UUID payload must be exactly 16 bytes" }
        return bytes.joinToString("") { byte ->
            val v = byte.toInt() and 0xFF
            "" + HEX_CHARS[v ushr 4] + HEX_CHARS[v and 0x0F]
        }
    }
}