package com.github.luckyguy73.appkilla.data.model

/**
 * Summary metrics displayed on the top system status banner.
 */
data class SystemStatus(
    val activeBackgroundAppsCount: Int = 0,
    val selectedCount: Int = 0,
    val totalMemoryFreedMb: Long = 0,
    val selectedAppsMemoryMb: Int = 0
)
