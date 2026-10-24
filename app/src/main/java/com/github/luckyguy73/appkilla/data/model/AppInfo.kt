package com.github.luckyguy73.appkilla.data.model

import android.graphics.drawable.Drawable

/**
 * Represents a TV / Fire OS app displayed in the task killer grid.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val memoryUsageMb: Int,
    val isSelected: Boolean = true,
    val isSystemApp: Boolean = false,
    val isRunning: Boolean = true
)
