package com.github.luckyguy73.appkilla.ui.viewmodel

import com.github.luckyguy73.appkilla.adb.AdbConnectionState
import com.github.luckyguy73.appkilla.data.model.AppInfo

/**
 * State container for the main TV Task Killer dashboard screen.
 */
data class MainUiState(
    val apps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isExecutingForceStop: Boolean = false,
    val totalMemoryFreedMb: Long = 0L,
    val isProcessScanAvailable: Boolean = false,
    val adbConnectionState: AdbConnectionState = AdbConnectionState.Disconnected,
    val userMessage: String? = null
) {
    val activeBackgroundAppsCount: Int get() = apps.size
    val selectedAppsCount: Int get() = apps.count { it.isSelected }
    val selectedAppsMemoryMb: Int get() = apps.filter { it.isSelected }.sumOf { it.memoryUsageMb }
}
