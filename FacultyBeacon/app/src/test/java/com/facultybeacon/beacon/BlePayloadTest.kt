package com.facultybeacon.beacon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class BlePayloadTest {

    @Test
    fun `uuid serializes to exactly 16 bytes`() {
        val uuid = UUID.randomUUID()
        val bytes = BlePayload.uuidToBytes(uuid)
        assertEquals(16, bytes.size)
    }

    @Test
    fun `round trip preserves the uuid`() {
        val uuid = UUID.fromString("123e4567-e89b-42d3-a456-426614174000")
        val bytes = BlePayload.uuidToBytes(uuid)
        assertEquals(uuid, BlePayload.bytesToUuid(bytes))
    }

    @Test
    fun `manufacturer data starts with ff ff`() {
        val uuid = UUID.randomUUID()
        val bytes = BlePayload.uuidToBytes(uuid)
        val manufacturerData = ByteArray(2 + 16).also { payload ->
            payload[0] = (BlePayload.MANUFACTURER_ID shr 8 and 0xFF).toByte() // 0xFF
            payload[1] = (BlePayload.MANUFACTURER_ID and 0xFF).toByte()       // 0xFF
            bytes.copyInto(payload, 2)
        }
        assertEquals(0xFF.toByte(), manufacturerData[0])
        assertEquals(0xFF.toByte(), manufacturerData[1])
        assertArrayEquals(bytes, manufacturerData.copyOfRange(2, 18))
    }
}