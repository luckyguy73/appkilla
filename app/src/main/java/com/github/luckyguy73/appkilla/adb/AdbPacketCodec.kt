package com.github.luckyguy73.appkilla.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Validates the fixed-size ADB packet header and its payload before a packet is
 * passed to the stream state machine.
 *
 * AppKilla advertises a 4096-byte maximum payload in CNXN, so larger inbound
 * payloads are rejected rather than allocated.
 */
internal object AdbPacketCodec {
    const val HEADER_SIZE = 24
    const val MAX_PAYLOAD_SIZE = 4096

    internal data class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataChecksum: Int,
        val magic: Int,
    )

    fun parseHeader(headerBytes: ByteArray): Header {
        if (headerBytes.size != HEADER_SIZE) {
            throw IOException("Invalid ADB header size: ${headerBytes.size}")
        }

        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val dataChecksum = buffer.int
        val magic = buffer.int

        if (command !in setOf(0x4E584E43, 0x48545541, 0x4E45504F, 0x59414B4F, 0x45534C43, 0x45545257)) {
            throw IOException("Unsupported ADB command: 0x${command.toString(16)}")
        }
        if (magic != (command xor -1)) {
            throw IOException("Invalid ADB command magic")
        }
        if (dataLength < 0 || dataLength > MAX_PAYLOAD_SIZE) {
            throw IOException("Invalid ADB payload length: $dataLength")
        }

        return Header(command, arg0, arg1, dataLength, dataChecksum, magic)
    }

    fun validatePayload(header: Header, payload: ByteArray) {
        if (payload.size != header.dataLength) {
            throw IOException(
                "ADB payload length mismatch: expected ${header.dataLength}, received ${payload.size}"
            )
        }

        val actualChecksum = checksum(payload)
        if (actualChecksum != header.dataChecksum) {
            throw IOException("Invalid ADB payload checksum")
        }
    }

    fun checksum(payload: ByteArray): Int {
        var checksum = 0
        for (byte in payload) {
            checksum += byte.toInt() and 0xFF
        }
        return checksum
    }
}

internal data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray,
)
