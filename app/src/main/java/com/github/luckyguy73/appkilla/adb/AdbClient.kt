package com.github.luckyguy73.appkilla.adb

import android.content.Context
import android.util.Log
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
 * Coroutine-based ADB TCP client for the ADB daemon running on this device.
 * Connects to the local ADB TCP endpoint, performs RSA handshake,
 * and executes shell commands such as `am force-stop <package>`.
 */
class AdbClient(
    private val context: Context,
    private val host: String = "127.0.0.1",
    private val port: Int = 5555,
    private val timeoutMs: Int = 5000,
) {

    companion object {
        private const val TAG = "AppKilla_AdbClient"

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

        private fun commandName(cmd: Int): String {
            return when (cmd) {
                A_CNXN -> "CNXN (0x4E584E43)"
                A_AUTH -> "AUTH (0x48545541)"
                A_OPEN -> "OPEN (0x4E45504F)"
                A_OKAY -> "OKAY (0x59414B4F)"
                A_CLSE -> "CLSE (0x45534C43)"
                A_WRTE -> "WRTE (0x45545257)"
                else -> String.format("UNKNOWN (0x%08X)", cmd)
            }
        }
    }

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private val localIdCounter = AtomicInteger(1)

    var connectionState: AdbConnectionState = AdbConnectionState.Disconnected
        private set

    /**
     * Connects to this device's ADB TCP daemon and completes authentication.
     */
    suspend fun connect(): AdbConnectionState = withContext(Dispatchers.IO) {
        Log.i(TAG, "Initiating local ADB TCP socket connection to $host:$port (timeout = ${timeoutMs}ms)...")
        try {
            connectionState = AdbConnectionState.Connecting

            Log.d(TAG, "Retrieving/generating RSA key pair for ADB authentication...")
            val keyPair = AdbCrypto.getOrCreateKeyPair(context)

            val newSocket = Socket()
            Log.d(TAG, "Connecting socket to InetSocketAddress($host, $port)...")
            newSocket.connect(InetSocketAddress(host, port), timeoutMs)
            newSocket.soTimeout = timeoutMs
            Log.i(TAG, "Socket connected to $host:$port successfully.")

            val inStream = DataInputStream(newSocket.getInputStream())
            val outStream = DataOutputStream(newSocket.getOutputStream())

            socket = newSocket
            inputStream = inStream
            outputStream = outStream

            // Step 1: Send CNXN packet
            val systemIdentity = "host::AppKilla\u0000".toByteArray(Charsets.UTF_8)
            Log.d(TAG, "Sending CNXN packet (VERSION=0x${VERSION.toString(16)}, MAX_PAYLOAD=$MAX_PAYLOAD, Identity='host::AppKilla')")
            writePacket(outStream, A_CNXN, VERSION, MAX_PAYLOAD, systemIdentity)

            // Step 2: Read response
            var response = readPacket(inStream)
            Log.d(TAG, "Received initial response: command=${commandName(response.command)}, arg0=${response.arg0}, dataLength=${response.data.size}")

            if (response.command == A_CNXN) {
                Log.i(TAG, "ADB daemon accepted connection immediately with CNXN response.")
                connectionState = AdbConnectionState.Connected
                return@withContext connectionState
            }

            if ((response.command == A_AUTH) && (response.arg0 == AUTH_TYPE_TOKEN)) {
                val token = response.data
                Log.d(TAG, "Received A_AUTH token challenge from ADB daemon (${token.size} bytes). Attempting signature auth...")

                // Try signature authentication
                val signature = AdbCrypto.signToken(keyPair.private, token)
                Log.d(TAG, "Sending signed AUTH token (AUTH_TYPE_SIGNATURE)...")
                writePacket(outStream, A_AUTH, AUTH_TYPE_SIGNATURE, 0, signature)

                response = readPacket(inStream)
                Log.d(TAG, "Received response after signature AUTH: command=${commandName(response.command)}, arg0=${response.arg0}")

                if (response.command == A_AUTH) {
                    Log.w(TAG, "ADB daemon rejected RSA signature (key not yet authorized). Sending public key payload for system popup...")
                    val pubKeyPayload = AdbCrypto.getAdbPublicKeyPayload(keyPair.public)
                    writePacket(outStream, A_AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, pubKeyPayload)

                    // Mark state as Unauthorized while user reviews popup on screen
                    connectionState = AdbConnectionState.Unauthorized

                    Log.i(TAG, "Public key sent. Awaiting user on-screen authorization on TV (60s timeout)...")
                    // Extend socket read timeout to 60s so socket remains alive while user taps 'Allow' on TV screen
                    newSocket.soTimeout = 60000

                    try {
                        val authResp = readPacket(inStream)
                        Log.d(TAG, "Received response after public key payload: command=${commandName(authResp.command)}, arg0=${authResp.arg0}")
                        if (authResp.command == A_CNXN) {
                            Log.i(TAG, "ADB daemon accepted public key on TV screen! State = Connected.")
                            newSocket.soTimeout = timeoutMs // Restore normal timeout
                            connectionState = AdbConnectionState.Connected
                            return@withContext connectionState
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "No CNXN response within 60s or error while waiting for user authorization: ${e.localizedMessage}")
                    }

                    newSocket.soTimeout = timeoutMs
                    return@withContext connectionState
                }
            }

            if (response.command == A_CNXN) {
                Log.i(TAG, "ADB authentication successful! State = Connected.")
                connectionState = AdbConnectionState.Connected
            } else {
                val errMsg = "Unexpected ADB response command: ${commandName(response.command)}"
                Log.e(TAG, errMsg)
                connectionState = AdbConnectionState.Error(errMsg)
            }

            connectionState
        } catch (e: Exception) {
            val failureDetail = "Failed to connect to local ADB socket ($host:$port): ${e.javaClass.simpleName} - ${e.localizedMessage}"
            Log.e(TAG, failureDetail, e)
            disconnect()
            connectionState = AdbConnectionState.Error(failureDetail)
            connectionState
        }
    }

    /**
     * Executes a shell command via ADB socket stream (e.g. "am force-stop com.example.app").
     * Returns true if successfully executed.
     */
    suspend fun executeShellCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Executing ADB shell command: '$command'")
        val outStream = outputStream
        if (outStream == null) {
            val err = "Cannot execute shell command '$command': outputStream is null (not connected)"
            Log.e(TAG, err)
            return@withContext Result.failure(IllegalStateException(err))
        }
        val inStream = inputStream
        if (inStream == null) {
            val err = "Cannot execute shell command '$command': inputStream is null (not connected)"
            Log.e(TAG, err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        try {
            val localId = localIdCounter.getAndIncrement()
            val destination = "shell:$command\u0000".toByteArray(Charsets.UTF_8)

            Log.d(TAG, "Sending A_OPEN packet for localId=$localId, destination='shell:$command'")
            writePacket(outStream, A_OPEN, localId, 0, destination)

            val outputBuilder = StringBuilder()
            var remoteId = 0
            var packetsProcessed = 0

            while (true) {
                val packet = readPacket(inStream)
                packetsProcessed++
                Log.d(TAG, "Shell packet #$packetsProcessed received: command=${commandName(packet.command)}, arg0=${packet.arg0}")

                when (packet.command) {
                    A_OKAY -> {
                        if (packet.arg1 != localId) {
                            throw java.io.IOException("ADB OKAY packet targeted local ID ${packet.arg1}, expected $localId")
                        }
                        if (remoteId != 0 && remoteId != packet.arg0) {
                            throw java.io.IOException("ADB remote stream ID changed unexpectedly")
                        }
                        remoteId = packet.arg0
                    }
                    A_WRTE -> {
                        if (packet.arg1 != localId || remoteId == 0 || packet.arg0 != remoteId) {
                            throw java.io.IOException("ADB WRTE packet has invalid stream identifiers")
                        }
                        outputBuilder.append(String(packet.data, Charsets.UTF_8))
                        writePacket(outStream, A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    A_CLSE -> {
                        if (packet.arg1 != localId) {
                            throw java.io.IOException("ADB CLSE packet targeted unexpected local ID ${packet.arg1}")
                        }
                        if (remoteId != 0 && packet.arg0 != remoteId) {
                            throw java.io.IOException("ADB CLSE packet has unexpected remote ID ${packet.arg0}")
                        }
                        writePacket(outStream, A_CLSE, localId, packet.arg0, ByteArray(0))
                        break
                    }
                    else -> throw java.io.IOException(
                        "Unexpected command while awaiting shell response: ${commandName(packet.command)}"
                    )
                }
            }

            val finalOutput = outputBuilder.toString()
            Log.i(TAG, "ADB shell command '$command' completed. Output (${finalOutput.length} chars): '$finalOutput'")
            Result.success(finalOutput)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during ADB shell command execution: '$command'", e)
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * Closes socket connection cleanly.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting ADB socket...")
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing ADB socket resources", e)
        }
        inputStream = null
        outputStream = null
        socket = null
        connectionState = AdbConnectionState.Disconnected
        Log.i(TAG, "ADB socket disconnected.")
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
        val headerBytes = ByteArray(AdbPacketCodec.HEADER_SIZE)
        inStream.readFully(headerBytes)
        val header = AdbPacketCodec.parseHeader(headerBytes)

        val data = ByteArray(header.dataLength)
        if (data.isNotEmpty()) inStream.readFully(data)
        AdbPacketCodec.validatePayload(header, data)

        return AdbPacket(header.command, header.arg0, header.arg1, data)
    }

    private fun calculateCrc(data: ByteArray): Int {
        return AdbPacketCodec.checksum(data)
    }

}