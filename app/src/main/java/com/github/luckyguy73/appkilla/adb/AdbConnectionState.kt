package com.github.luckyguy73.appkilla.adb

/**
 * Represents the current status of AppKilla's local process scan.
 */
sealed interface AdbConnectionState {

    /** Socket disconnected */
    object Disconnected : AdbConnectionState

    /** Reading the device process list */
    object Connecting : AdbConnectionState

    /** Process scan completed successfully */
    object Connected : AdbConnectionState

    /**
     * Retained for UI compatibility; this scan no longer uses ADB authorization.
     */
    object Unauthorized : AdbConnectionState

    /** Connection or execution error */
    data class Error(val message: String) : AdbConnectionState
}
