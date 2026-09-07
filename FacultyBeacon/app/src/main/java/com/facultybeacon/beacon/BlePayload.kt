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

    /** Serializes a UUID to its canonical 16 bytes, big-endian (MSB first). */
    fun uuidToBytes(uuid: java.util.UUID): ByteArray {
        return java.nio.ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
    }

    /** Reconstructs a UUID from the 16 bytes produced by [uuidToBytes] (for tests/debug). */
    fun bytesToUuid(bytes: ByteArray): java.util.UUID {
        require(bytes.size == 16) { "UUID payload must be exactly 16 bytes" }
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return java.util.UUID(buffer.long, buffer.long)
    }
}