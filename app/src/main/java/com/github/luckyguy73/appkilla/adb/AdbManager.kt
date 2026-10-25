package com.github.luckyguy73.appkilla.adb

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dadb.AdbConnectException
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.EOFException
import java.net.InetAddress

/** Uses DADB to authenticate to the device's own adbd and run shell commands. */
class AdbManager(private val context: Context) {

    companion object {
        private const val TAG = "AppKilla_Dadb"
        private const val ADB_PORT = 5555
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val keyPair: AdbKeyPair by lazy { loadOrCreateKeyPair() }
    private val _connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    val connectionState: StateFlow<AdbConnectionState> = _connectionState

    fun connect() {
        scope.launch { getRunningProcessNames() }
    }

    suspend fun getRunningProcessNames(): Set<String>? = mutex.withLock {
        _connectionState.value = AdbConnectionState.Connecting
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (host in deviceAddresses()) {
                try {
                    Log.i(TAG, "Connecting to device adbd at $host:$ADB_PORT")
                    Dadb.create(host, ADB_PORT, keyPair, connectTimeout = 4_000, socketTimeout = 8_000).use { dadb ->
                        val response = dadb.shell("ps -A")
                        if (response.exitCode != 0) {
                            throw IllegalStateException("ps -A exited ${response.exitCode}: ${response.allOutput}")
                        }
                        val processes = RunningProcessParser.parse(response.output)
                            ?: throw IllegalStateException("ps -A returned an empty response")
                        Log.i(TAG, "DADB process snapshot contains ${processes.size} app process names")
                        _connectionState.value = AdbConnectionState.Connected
                        return@withContext processes
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.e(TAG, "DADB connection/shell failed at $host:$ADB_PORT", e)
                }
            }
            val error = lastError
            val failureMessage = when {
                Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
                    error is AdbConnectException && error.hasEofCause() ->
                    "This Fire TV closes AppKilla's local ADB handshake before sending an RSA authorization challenge. This Fire OS build blocks in-device ADB, so AppKilla cannot verify running apps or force-stop them."
                else -> "DADB could not read device processes${error?.let { ": ${it.localizedMessage ?: it.javaClass.simpleName}" } ?: ""}"
            }
            _connectionState.value = AdbConnectionState.Error(
                failureMessage
            )
            null
        }
    }

    suspend fun forceStopApp(packageName: String): Boolean = mutex.withLock {
        val command = AdbCommandResult.forceStopCommand(packageName) ?: return@withLock false
        withContext(Dispatchers.IO) {
            for (host in deviceAddresses()) {
                try {
                    Dadb.create(host, ADB_PORT, keyPair, connectTimeout = 4_000, socketTimeout = 8_000).use { dadb ->
                        val before = RunningProcessParser.parse(dadb.shell("ps -A").output) ?: return@use false
                        if (packageName !in before && before.none { it.startsWith("$packageName:") }) return@use false
                        val response = dadb.shell(command)
                        val status = AdbCommandResult.parseExitCode(response.output)
                        if (status != 0) return@use false
                        val after = RunningProcessParser.parse(dadb.shell("ps -A").output) ?: return@use false
                        val stillRunning = packageName in after || after.any { it.startsWith("$packageName:") }
                        Log.i(TAG, "Force-stop $packageName exit=$status stillRunning=$stillRunning")
                        _connectionState.value = AdbConnectionState.Connected
                        return@withContext !stillRunning
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DADB force-stop failed at $host:$ADB_PORT", e)
                }
            }
            false
        }
    }

    private fun loadOrCreateKeyPair(): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists()) AdbKeyPair.generate(privateKey, publicKey)
        return AdbKeyPair.read(privateKey, publicKey)
    }

    private fun Throwable.hasEofCause(): Boolean = generateSequence(this) { it.cause }.any { it is EOFException }


    /** Try loopback first, then this device's active Wi-Fi address for vendor adbd routing. */
    private fun deviceAddresses(): List<String> {
        val addresses = linkedSetOf("127.0.0.1", "localhost")
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val bytes = byteArrayOf(ip.toByte(), (ip shr 8).toByte(), (ip shr 16).toByte(), (ip shr 24).toByte())
                InetAddress.getByAddress(bytes).hostAddress?.let(addresses::add)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine the TV Wi-Fi address", e)
        }
        return addresses.toList()
    }

    fun requestShellAccess() = connect()

    fun disconnect() {
        _connectionState.value = AdbConnectionState.Disconnected
    }
}
