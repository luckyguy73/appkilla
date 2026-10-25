package com.github.luckyguy73.appkilla.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.luckyguy73.appkilla.adb.AdbManager
import com.github.luckyguy73.appkilla.data.repository.AppRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val appRepository: AppRepository,
    private val adbManager: AdbManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AppKilla_ViewModel"
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        Log.i(TAG, "Initializing MainViewModel...")
        observeAdbConnection()
        loadApps()
    }

    private fun observeAdbConnection() {
        viewModelScope.launch {
            adbManager.connectionState.collect { state ->
                Log.i(TAG, "Shell access state changed -> $state")
                _uiState.update { it.copy(adbConnectionState = state) }
            }
        }
    }

    fun loadApps() {
        if (_uiState.value.isLoading || _uiState.value.isExecutingForceStop) return

        Log.i(TAG, "loadApps() invoked. Starting a fresh running-process scan...")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            try {
                val scanUsedAdb = refreshRunningAppsInternal()
                if (!scanUsedAdb) {
                    _uiState.update {
                        it.copy(userMessage = "${com.github.luckyguy73.appkilla.adb.AdbErrorCode.PROCESS_SCAN_FAILED}: Shell process scan unavailable. Showing only processes Android confirms are running.")
                    }
                }
                Log.i(TAG, "loadApps() complete.")
            } catch (e: Exception) {
                Log.e(TAG, "App scan failed", e)
                _uiState.update {
                    it.copy(userMessage = com.github.luckyguy73.appkilla.adb.AdbErrorCode.message(com.github.luckyguy73.appkilla.adb.AdbErrorCode.APP_SCAN_FAILED, "Unable to scan running apps: ${e.localizedMessage ?: "unknown error"}"))
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Waits for an actual device process snapshot, then replaces the grid with
     * apps present in that snapshot. Returns false when the shell scan is unavailable.
     */
    private suspend fun refreshRunningAppsInternal(): Boolean {
        val deviceProcessNames = adbManager.getRunningProcessNames()
        val currentRunningApps = appRepository.getApps(deviceProcessNames)
        val savedFreedMb = appRepository.getSavedMemoryFreedMb()

        _uiState.update { state ->
            val existingSelections = state.apps.associate { it.packageName to it.isSelected }
            val updatedApps = currentRunningApps.map { app ->
                app.copy(isSelected = existingSelections[app.packageName] ?: false)
            }

            state.copy(
                apps = updatedApps,
                totalMemoryFreedMb = savedFreedMb,
                isProcessScanAvailable = deviceProcessNames != null
            )
        }
        return deviceProcessNames != null
    }

    fun toggleAppSelection(packageName: String) {
        _uiState.update { state ->
            val updatedList = state.apps.map { app ->
                if (app.packageName == packageName) {
                    val newSelection = !app.isSelected
                    Log.d(TAG, "App selection toggled for '${app.appName}' ($packageName): $newSelection")
                    app.copy(isSelected = newSelection)
                } else {
                    app
                }
            }
            state.copy(apps = updatedList)
        }
    }

    fun toggleSelectAll() {
        _uiState.update { state ->
            val shouldSelectAll = state.apps.any { !it.isSelected }
            Log.i(TAG, "toggleSelectAll() called. Setting all apps selected = $shouldSelectAll")
            state.copy(apps = state.apps.map { it.copy(isSelected = shouldSelectAll) })
        }
    }

    fun forceCloseSelectedApps() {
        if (_uiState.value.isExecutingForceStop || _uiState.value.isLoading) return

        viewModelScope.launch {
            val selectedApps = _uiState.value.apps.filter { it.isSelected }
            Log.i(TAG, "forceCloseSelectedApps() requested. Selected count = ${selectedApps.size}")

            if (selectedApps.isEmpty()) {
                _uiState.update { it.copy(userMessage = "No apps selected! Please select apps to close.") }
                return@launch
            }

            _uiState.update { it.copy(isExecutingForceStop = true, userMessage = null) }
            try {
                val killResult = appRepository.killSelectedApps(selectedApps)

                // Let the system settle, then take a new device-wide snapshot.
                delay(500)
                val scanUsedAdb = refreshRunningAppsInternal()
                val updatedFreedMb = appRepository.getSavedMemoryFreedMb()

                val feedbackMsg = when {
                    !scanUsedAdb ->
                        "${com.github.luckyguy73.appkilla.adb.AdbErrorCode.PROCESS_SCAN_FAILED}: Force-stop finished, but the shell process scan is unavailable; only Android-visible running processes can be shown."
                    killResult.closedCount > 0 ->
                        "Force-stop command succeeded for ${killResult.closedCount} app(s). Memory reclaimed is not measured."
                    !killResult.isShellConnected ->
                        "${com.github.luckyguy73.appkilla.adb.AdbErrorCode.ADB_UNAVAILABLE}: Shell access required: unable to force stop apps (${killResult.connectionState})."
                    else ->
                        "${com.github.luckyguy73.appkilla.adb.AdbErrorCode.FORCE_STOP_FAILED}: Unable to force stop selected apps (${selectedApps.size} attempted). Check device logs."
                }

                _uiState.update {
                    it.copy(
                        totalMemoryFreedMb = updatedFreedMb,
                        isExecutingForceStop = false,
                        userMessage = feedbackMsg
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Force-stop or follow-up scan failed", e)
                _uiState.update {
                    it.copy(
                        isExecutingForceStop = false,
                        userMessage = com.github.luckyguy73.appkilla.adb.AdbErrorCode.message(com.github.luckyguy73.appkilla.adb.AdbErrorCode.FORCE_STOP_REFRESH_FAILED, "Force-stop/refresh failed: ${e.localizedMessage ?: "unknown error"}")
                    )
                }
            }
        }
    }

    fun reconnectAdb() {
        Log.i(TAG, "Shell access requested by user.")
        adbManager.requestShellAccess()
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    class Factory(
        private val application: Application,
        private val appRepository: AppRepository,
        private val adbManager: AdbManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(application, appRepository, adbManager) as T
        }
    }
}
