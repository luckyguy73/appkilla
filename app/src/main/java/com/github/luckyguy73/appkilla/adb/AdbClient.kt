package com.github.luckyguy73.appkilla.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * High performance, coroutine-based local ADB TCP client.
 * Connects to 127.0.0.1:5555, performs RSA handshake,
 * and executes shell commands such as `am force-stop <package>`.
 */
class AdbClient(
    private val context: Context,
    private val host: String = "127.0.0.1",
    private val port: Int = 5555,
    private val timeoutMs: Int = 5000,
) {

    companion object {
        const val A_CNXN = 0x4E584E43 // "CNXN"
        const val A_AUTH = 0x48545541 // "AUTH"
        const val A_OPEN = 0x4E45504F // "OPEN"
        const val A_OKAY = 0x59414B4F // "OKAY"
        const val A_CLSE = 0x45534C43 // "CLSE"
        const val A_WRTE = 0x45545257 // "WRTE"

        const val AUTH_TYPE_TOKEN = 1
        const val AUTH_TYPE_SIGNATURE = 2
        const val AUTH_TYPE_RSAPUBLICKEY = 3

        const val MAX_PAYLOAD = 4096
        const val VERSION = 0x01000000
    }

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private val localIdCounter = AtomicInteger(1)

    var connectionState: AdbConnectionState = AdbConnectionState.Disconnected
        private set

    /**
     * Connects to the local ADB TCP daemon and completes authentication.
     */
    suspend fun connect(): AdbConnectionState = withContext(Dispatchers.IO) {
        try {
            connectionState = AdbConnectionState.Connecting

            val keyPair = AdbCrypto.getOrCreateKeyPair(context)

            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(host, port), timeoutMs)
            newSocket.soTimeout = timeoutMs

            val inStream = DataInputStream(newSocket.getInputStream())
            val outStream = DataOutputStream(newSocket.getOutputStream())

            socket = newSocket
            inputStream = inStream
            outputStream = outStream

            // Step 1: Send CNXN packet
            val systemIdentity = "host::AppKilla\u0000".toByteArray(Charsets.UTF_8)
            writePacket(outStream, A_CNXN, VERSION, MAX_PAYLOAD, systemIdentity)

            // Step 2: Read response
            var response = readPacket(inStream)

            if (response.command == A_CNXN) {
                connectionState = AdbConnectionState.Connected
                return@withContext connectionState
            }

            if ((response.command == A_AUTH) && (response.arg0 == AUTH_TYPE_TOKEN)) {
                val token = response.data

                // Try signature authentication
                val signature = AdbCrypto.signToken(keyPair.private, token)
                writePacket(outStream, A_AUTH, AUTH_TYPE_SIGNATURE, 0, signature)

                response = readPacket(inStream)

                if (response.command == A_AUTH) {
                    // Signature rejected -> Send public key for authorization dialog
                    val pubKeyPayload = AdbCrypto.getAdbPublicKeyPayload(keyPair.public)
                    writePacket(outStream, A_AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, pubKeyPayload)

                    connectionState = AdbConnectionState.Unauthorized
                    return@withContext connectionState
                }
            }

            connectionState = if (response.command == A_CNXN) {
                AdbConnectionState.Connected
            } else {
                AdbConnectionState.Error("Unexpected response: ${response.command}")
            }

            connectionState
        } catch (e: Exception) {
            disconnect()
            connectionState = AdbConnectionState.Error(e.localizedMessage ?: "Failed to connect to ADB on 127.0.0.1:5555")
            connectionState
        }
    }

    /**
     * Executes a shell command via ADB socket stream (e.g. "am force-stop com.example.app").
     * Returns true if successfully executed.
     */
    suspend fun executeShellCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        val outStream = outputStream
            ?: return@withContext Result.failure(IllegalStateException("ADB client is not connected"))
        val inStream = inputStream
            ?: return@withContext Result.failure(IllegalStateException("ADB client is not connected"))

        try {
            val localId = localIdCounter.getAndIncrement()
            val destination = "shell:$command\u0000".toByteArray(Charsets.UTF_8)

            // Send A_OPEN packet
            writePacket(outStream, A_OPEN, localId, 0, destination)

            val outputBuilder = StringBuilder()
            var remoteId = 0

            while (true) {
                val packet = readPacket(inStream)
                when (packet.command) {
                    A_OKAY -> {
                        remoteId = packet.arg0
                    }
                    A_WRTE -> {
                        if (packet.data.isNotEmpty()) {
                            outputBuilder.append(String(packet.data, Charsets.UTF_8))
                        }
                        // ACK write packet
                        writePacket(outStream, A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    A_CLSE -> {
                        // Channel closed by server
                        writePacket(outStream, A_CLSE, localId, remoteId, ByteArray(0))
                        break
                    }
                }
            }

            Result.success(outputBuilder.toString())
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * Closes socket connection cleanly.
     */
    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        connectionState = AdbConnectionState.Disconnected
    }

    private fun writePacket(
        out: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        data: ByteArray
    ) {
        val magic = command xor -0x1
        val crc = calculateCrc(data)

        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(crc)
        header.putInt(magic)

        synchronized(out) {
            out.write(header.array())
            if (data.isNotEmpty()) {
                out.write(data)
            }
            out.flush()
        }
    }

    private fun readPacket(inStream: DataInputStream): AdbPacket {
        val headerBytes = ByteArray(24)
        inStream.readFully(headerBytes)

        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        buffer.int // arg1
        val dataLength = buffer.int
        buffer.int // dataCrc
        buffer.int // magic

        val data = if (dataLength > 0) {
            val dataBytes = ByteArray(dataLength)
            inStream.readFully(dataBytes)
            dataBytes
        } else {
            ByteArray(0)
        }

        return AdbPacket(command, arg0, data)
    }

    private fun calculateCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc += (b.toInt() and 0xFF)
        }
        return crc
    }

    private class AdbPacket(
        val command: Int,
        val arg0: Int,
        val data: ByteArray
    )
}
