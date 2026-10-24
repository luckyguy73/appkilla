package com.github.luckyguy73.appkilla.adb

import android.content.Context
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var client: AdbClient? = null

    private val _connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()

    fun connect() {
        scope.launch {
            mutex.withLock {
                ensureConnectedInternal(forceNewHandshake = true)
            }
        }
    }

    suspend fun forceStopApp(packageName: String): Boolean = mutex.withLock {
        // If state is Unauthorized or Error, don't trigger repeating public key authorization popups on button clicks!
        if (_connectionState.value is AdbConnectionState.Unauthorized) {
            return false
        }

        if (_connectionState.value !is AdbConnectionState.Connected) {
            val state = ensureConnectedInternal(forceNewHandshake = false)
            if (state !is AdbConnectionState.Connected) {
                return false
            }
        }

        val activeClient = client ?: return false
        val result = activeClient.executeShellCommand("am force-stop $packageName")

        return if (result.isSuccess) {
            true
        } else {
            _connectionState.value = AdbConnectionState.Disconnected
            client?.disconnect()
            client = null
            false
        }
    }

    private suspend fun ensureConnectedInternal(forceNewHandshake: Boolean): AdbConnectionState {
        if (_connectionState.value is AdbConnectionState.Connected && client != null && !forceNewHandshake) {
            return _connectionState.value
        }

        if (!forceNewHandshake && _connectionState.value is AdbConnectionState.Unauthorized) {
            return _connectionState.value
        }

        _connectionState.value = AdbConnectionState.Connecting
        client?.disconnect()
        
        val newClient = AdbClient(context)
        val state = newClient.connect()

        if (state is AdbConnectionState.Connected) {
            client = newClient
            _connectionState.value = AdbConnectionState.Connected
        } else {
            client = null
            _connectionState.value = state
        }

        return state
    }

    fun disconnect() {
        scope.launch {
            mutex.withLock {
                client?.disconnect()
                client = null
                _connectionState.value = AdbConnectionState.Disconnected
            }
        }
    }
}
