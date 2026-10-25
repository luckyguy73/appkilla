package com.github.luckyguy73.appkilla.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbPacketCodecTest {
    @Test
    fun parsesValidHeaderAndPayload() {
        val payload = byteArrayOf(1, 2, 255.toByte())
        val header = AdbPacketCodec.parseHeader(header(payload))

        assertEquals(3, header.dataLength)
        assertEquals(payload.size, header.dataLength)
        AdbPacketCodec.validatePayload(header, payload)
    }

    @Test
    fun rejectsHeaderWithInvalidMagic() {
        assertThrows( java.io.IOException::class.java) {
            AdbPacketCodec.parseHeader(header(byteArrayOf(), magicOverride = 0))
        }
    }

    @Test
    fun rejectsNegativePayloadLength() {
        assertThrows(java.io.IOException::class.java) {
            AdbPacketCodec.parseHeader(header(byteArrayOf(), lengthOverride = -1))
        }
    }

    @Test
    fun rejectsPayloadLargerThanAdvertisedMaximum() {
        assertThrows(java.io.IOException::class.java) {
            AdbPacketCodec.parseHeader(
                header(byteArrayOf(), lengthOverride = AdbPacketCodec.MAX_PAYLOAD_SIZE + 1)
            )
        }
    }

    @Test
    fun rejectsPayloadChecksumMismatch() {
        val payload = byteArrayOf(10, 20)
        val parsed = AdbPacketCodec.parseHeader(header(payload, checksumOverride = 0))
        assertThrows(java.io.IOException::class.java) {
            AdbPacketCodec.validatePayload(parsed, payload)
        }
    }

    @Test
    fun rejectsTruncatedHeader() {
        assertThrows(java.io.IOException::class.java) {
            AdbPacketCodec.parseHeader(ByteArray(AdbPacketCodec.HEADER_SIZE - 1))
        }
    }

    @Test
    fun rejectsUnknownCommand() {
        assertThrows(java.io.IOException::class.java) {
            AdbPacketCodec.parseHeader(header(byteArrayOf()).also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x12345678)
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0x12345678 xor -1)
            })
        }
    }

    private fun header(
        payload: ByteArray,
        lengthOverride: Int = payload.size,
        checksumOverride: Int? = null,
        magicOverride: Int? = null,
    ): ByteArray {
        val command = 0x45545257 // WRTE
        val checksum = checksumOverride ?: AdbPacketCodec.checksum(payload)
        return ByteBuffer.allocate(AdbPacketCodec.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(7)
            .putInt(1)
            .putInt(lengthOverride)
            .putInt(checksum)
            .putInt(magicOverride ?: (command xor -1))
            .array()
    }
}
