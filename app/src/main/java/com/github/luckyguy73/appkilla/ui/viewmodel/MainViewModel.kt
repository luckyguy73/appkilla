package com.github.luckyguy73.appkilla.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.luckyguy73.appkilla.adb.AdbManager
import com.github.luckyguy73.appkilla.data.repository.AppRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val appRepository: AppRepository,
    private val adbManager: AdbManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        observeAdbConnection()
        loadApps()
        startPeriodicAppCheck()
    }

    private fun observeAdbConnection() {
        viewModelScope.launch {
            adbManager.connectionState.collect { state ->
                _uiState.update { it.copy(adbConnectionState = state) }
            }
        }
    }

    private fun startPeriodicAppCheck() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(3000)
                if (!_uiState.value.isExecutingForceStop && !_uiState.value.isLoading) {
                    refreshRunningAppsInternal()
                }
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            adbManager.connect()

            refreshRunningAppsInternal()

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun refreshRunningAppsInternal() {
        val currentRunningApps = appRepository.getApps()
        val savedFreedMb = appRepository.getSavedMemoryFreedMb()

        _uiState.update { state ->
            val existingSelections = state.apps.associate { it.packageName to it.isSelected }

            val updatedApps = currentRunningApps.map { app ->
                val isSelected = existingSelections[app.packageName] ?: true
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
                    app.copy(isSelected = !app.isSelected)
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
            val updatedList = state.apps.map { it.copy(isSelected = shouldSelectAll) }
            state.copy(apps = updatedList)
        }
    }

    fun forceCloseSelectedApps() {
        viewModelScope.launch {
            val selectedApps = _uiState.value.apps.filter { it.isSelected }
            if (selectedApps.isEmpty()) {
                _uiState.update { it.copy(userMessage = "No apps selected!") }
                return@launch
            }

            _uiState.update { it.copy(isExecutingForceStop = true, userMessage = null) }

            val freedMb = appRepository.killSelectedApps(selectedApps)

            // Small delay for OS process cleanup
            delay(500)

            refreshRunningAppsInternal()

            val updatedFreedMb = appRepository.getSavedMemoryFreedMb()

            _uiState.update {
                it.copy(
                    totalMemoryFreedMb = updatedFreedMb,
                    isExecutingForceStop = false,
                    userMessage = "Force-closed ${selectedApps.size} apps. $freedMb MB memory freed!"
                )
            }
        }
    }

    fun reconnectAdb() {
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
