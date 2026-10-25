package com.github.luckyguy73.appkilla.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Singleton manager that controls ADB socket lifecycle,
 * auto-reconnections, and force-stop command execution.
 */
class AdbManager(private val context: Context) {

    companion object {
        private const val TAG = "AppKilla_AdbManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var client: AdbClient? = null

    private val _connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()

    fun connect() {
        Log.i(TAG, "AdbManager.connect() requested")
        scope.launch {
            mutex.withLock {
                ensureConnectedInternal(forceNewHandshake = true)
            }
        }
    }

    /**
     * Returns process names from the target Android device. A null result means
     * the scan could not be completed; an empty set is a successful scan with
     * no matching application processes.
     */
    suspend fun getRunningProcessNames(): Set<String>? = mutex.withLock {
        val state = ensureConnectedInternal(forceNewHandshake = false)
        if (state !is AdbConnectionState.Connected) {
            Log.w(TAG, "Cannot scan running processes because ADB is not connected: $state")
            return@withLock null
        }

        val activeClient = client ?: return@withLock null
        val result = activeClient.executeShellCommand("ps -A")
        val output = result.getOrNull()
        if (output == null) {
            Log.w(TAG, "ADB process scan failed: ${result.exceptionOrNull()?.localizedMessage}")
            _connectionState.value = AdbConnectionState.Disconnected
            client?.disconnect()
            client = null
            return@withLock null
        }

        val processNames = RunningProcessParser.parse(output)
        if (processNames == null) {
            Log.w(TAG, "ADB process scan returned empty output")
        } else {
            Log.i(TAG, "ADB process scan found ${processNames.size} package-style processes")
        }
        processNames
    }

    suspend fun forceStopApp(packageName: String): Boolean = mutex.withLock {
        Log.i(TAG, "AdbManager.forceStopApp() called for package: '$packageName'")
        val command = AdbCommandResult.forceStopCommand(packageName)
        if (command == null) {
            Log.w(TAG, "Rejected invalid package name for force-stop")
            return false
        }

        var currentState = _connectionState.value
        Log.d(TAG, "Current ADB connection state prior to forceStop: $currentState")

        // If already connected with an active client, execute directly without re-opening socket!
        if (currentState is AdbConnectionState.Connected && client != null) {
            val activeClient = client!!
            Log.d(TAG, "Reusing existing connected ADB socket for 'am force-stop $packageName'...")
            val result = activeClient.executeShellCommand(command)
            if (result.isSuccess) {
                val exitCode = result.getOrNull()?.let(AdbCommandResult::parseExitCode)
                if (exitCode == 0) {
                    Log.i(TAG, "Force-stop shell command returned exit status 0 for package: '$packageName'")
                    return true
                }
                Log.w(TAG, "Force-stop command failed or returned no valid exit status for '$packageName' (exitCode=$exitCode)")
                return false
            } else {
                Log.w(TAG, "ADB transport failed on existing client socket. Reconnecting...")
                _connectionState.value = AdbConnectionState.Disconnected
                client?.disconnect()
                client = null
            }
        }

        // If not connected, attempt connection handshake
        Log.d(TAG, "ADB socket not currently connected. Attempting connection handshake...")
        currentState = ensureConnectedInternal(forceNewHandshake = false)
        if (currentState !is AdbConnectionState.Connected) {
            Log.w(TAG, "ADB connection attempt finished with state: $currentState. Cannot execute force stop for '$packageName'.")
            return false
        }

        val activeClient = client
        if (activeClient == null) {
            Log.e(TAG, "Active client is null despite state being Connected. Force stop aborted for '$packageName'.")
            return false
        }

        Log.d(TAG, "Sending 'am force-stop $packageName' command via newly connected ADB client...")
        val result = activeClient.executeShellCommand(command)
        val exitCode = result.getOrNull()?.let(AdbCommandResult::parseExitCode)

        return if (exitCode == 0) {
            Log.i(TAG, "Successfully executed force stop for package: '$packageName'")
            true
        } else {
            Log.e(TAG, "Force-stop command failed or returned no valid exit status for '$packageName' (exitCode=$exitCode): ${result.exceptionOrNull()?.localizedMessage}")
            _connectionState.value = AdbConnectionState.Disconnected
            client?.disconnect()
            client = null
            false
        }
    }

    private suspend fun ensureConnectedInternal(forceNewHandshake: Boolean): AdbConnectionState {
        val current = _connectionState.value
        Log.d(TAG, "ensureConnectedInternal(forceNewHandshake=$forceNewHandshake) - current state: $current")

        if (current is AdbConnectionState.Connected && client != null && !forceNewHandshake) {
            Log.d(TAG, "Already connected and valid client exists. Skipping new handshake.")
            return current
        }

        Log.i(TAG, "Initiating new AdbClient handshake...")
        _connectionState.value = AdbConnectionState.Connecting
        client?.disconnect()
        
        var newClient = AdbClient(context)
        var state = newClient.connect()

        // Some Fire OS versions accept in-device ADB only on loopback, while others
        // accept it on the Wi-Fi address. Try the other endpoint after a transport error.
        if (state is AdbConnectionState.Error && newClient.host != "127.0.0.1") {
            val firstError = state.message
            newClient.disconnect()
            val loopbackClient = AdbClient(context, host = "127.0.0.1")
            val loopbackState = loopbackClient.connect()
            if (loopbackState is AdbConnectionState.Connected ||
                loopbackState is AdbConnectionState.Unauthorized
            ) {
                newClient = loopbackClient
                state = loopbackState
            } else {
                loopbackClient.disconnect()
                state = AdbConnectionState.Error(
                    "ADB connection failed on both device network address and 127.0.0.1. " +
                        "Network endpoint: $firstError; loopback endpoint: ${if (loopbackState is AdbConnectionState.Error) loopbackState.message else loopbackState}"
                )
            }
        }

        if (state is AdbConnectionState.Connected) {
            Log.i(TAG, "ADB connection established successfully.")
            client = newClient
            _connectionState.value = AdbConnectionState.Connected
        } else {
            // Unauthorized/error clients are not retained by the manager. In particular,
            // the authorization wait may time out with a live socket; close it before a
            // later reconnect creates another client.
            newClient.disconnect()
            Log.w(TAG, "ADB connection attempt ended with state: $state")
            client = null
            _connectionState.value = state
        }

        return state
    }

    fun disconnect() {
        Log.i(TAG, "AdbManager.disconnect() requested")
        scope.launch {
            mutex.withLock {
                client?.disconnect()
                client = null
                _connectionState.value = AdbConnectionState.Disconnected
                Log.i(TAG, "AdbManager disconnected.")
            }
        }
    }
}