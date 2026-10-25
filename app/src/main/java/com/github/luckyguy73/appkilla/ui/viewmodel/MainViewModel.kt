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
                Log.i(TAG, "ADB Connection state changed -> $state")
                _uiState.update { it.copy(adbConnectionState = state) }
            }
        }
    }

    fun loadApps() {
        Log.i(TAG, "loadApps() invoked. Initiating ADB connect and app scan...")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            adbManager.connect()

            refreshRunningAppsInternal()

            _uiState.update { it.copy(isLoading = false) }
            Log.i(TAG, "loadApps() complete. UI state updated.")
        }
    }

    private suspend fun refreshRunningAppsInternal() {
        val currentRunningApps = appRepository.getApps()
        val savedFreedMb = appRepository.getSavedMemoryFreedMb()

        _uiState.update { state ->
            val existingSelections = state.apps.associate { it.packageName to it.isSelected }

            val updatedApps = currentRunningApps.map { app ->
                // Default to unselected (false) for new apps
                val isSelected = existingSelections[app.packageName] ?: false
                app.copy(isSelected = isSelected)
            }

            state.copy(
                apps = updatedApps,
                totalMemoryFreedMb = savedFreedMb
            )
        }
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
            val updatedList = state.apps.map { it.copy(isSelected = shouldSelectAll) }
            state.copy(apps = updatedList)
        }
    }

    fun forceCloseSelectedApps() {
        viewModelScope.launch {
            val selectedApps = _uiState.value.apps.filter { it.isSelected }
            Log.i(TAG, "forceCloseSelectedApps() requested. Selected count = ${selectedApps.size}")
            
            if (selectedApps.isEmpty()) {
                Log.w(TAG, "forceCloseSelectedApps() canceled: No apps selected by user.")
                _uiState.update { it.copy(userMessage = "No apps selected! Please select apps to close.") }
                return@launch
            }

            _uiState.update { it.copy(isExecutingForceStop = true, userMessage = null) }

            val killResult = appRepository.killSelectedApps(selectedApps)

            // Small delay for OS process cleanup
            delay(500)

            refreshRunningAppsInternal()

            val updatedFreedMb = appRepository.getSavedMemoryFreedMb()

            val feedbackMsg = when {
                killResult.closedCount > 0 -> {
                    "Force-closed ${killResult.closedCount} app(s). ${killResult.freedMb} MB memory freed!"
                }
                !killResult.isAdbConnected -> {
                    "ADB Connection Required: Unable to force close apps. See ADB status banner above (${killResult.adbState})."
                }
                else -> {
                    "Unable to force close selected apps (${selectedApps.size} attempted). Check device logs."
                }
            }

            Log.i(TAG, "forceCloseSelectedApps() completed. Feedback message: '$feedbackMsg'")

            _uiState.update {
                it.copy(
                    totalMemoryFreedMb = updatedFreedMb,
                    isExecutingForceStop = false,
                    userMessage = feedbackMsg
                )
            }
        }
    }

    fun reconnectAdb() {
        Log.i(TAG, "reconnectAdb() requested by user.")
        adbManager.connect()
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