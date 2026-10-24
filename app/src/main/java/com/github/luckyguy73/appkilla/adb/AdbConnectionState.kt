package com.github.luckyguy73.appkilla.adb

/**
 * Represents the current status of the local ADB socket connection.
 */
sealed interface AdbConnectionState {

    /** Socket disconnected */
    object Disconnected : AdbConnectionState

    /** Attempting TCP socket handshake */
    object Connecting : AdbConnectionState

    /** ADB socket connected and authenticated successfully */
    object Connected : AdbConnectionState

    /**
     * Connected to ADB TCP port, but RSA key is not yet authorized by the device.
     * User needs to accept the "Allow ADB debugging?" system dialog on screen.
     */
    object Unauthorized : AdbConnectionState

    /** Connection or execution error */
    data class Error(val message: String) : AdbConnectionState
}
