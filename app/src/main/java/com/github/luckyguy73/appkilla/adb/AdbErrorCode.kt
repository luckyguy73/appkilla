package com.github.luckyguy73.appkilla.adb

/**
 * Stable, user-reportable diagnostic codes for errors surfaced by AppKilla.
 * Keep existing code meanings unchanged so reports can be matched to logs.
 */
object AdbErrorCode {
    const val SOCKET_CONNECTION_FAILED = "ADB-001"
    const val UNEXPECTED_HANDSHAKE_RESPONSE = "ADB-002"
    const val OUTPUT_STREAM_UNAVAILABLE = "ADB-003"
    const val INPUT_STREAM_UNAVAILABLE = "ADB-004"
    const val SHELL_TRANSPORT_FAILED = "ADB-005"
    const val BOTH_ENDPOINTS_FAILED = "ADB-006"
    const val PROCESS_SCAN_FAILED = "ADB-007"
    const val PROCESS_SCAN_EMPTY = "ADB-008"
    const val APP_SCAN_FAILED = "ADB-009"
    const val FORCE_STOP_REFRESH_FAILED = "ADB-010"
    const val FORCE_STOP_FAILED = "ADB-011"
    const val ADB_UNAVAILABLE = "ADB-012"

    fun message(code: String, detail: String): String = "$code: $detail"
}
