package com.github.luckyguy73.appkilla.adb

/**
 * Builds and interprets force-stop shell commands without accepting arbitrary
 * shell input. A zero exit status means the shell command succeeded; it does
 * not independently prove that the package had a running process.
 */
internal object AdbCommandResult {
    const val EXIT_STATUS_MARKER = "__APPKILLA_EXIT_CODE__:"
    private const val DOLLAR = '$'

    private val packageNamePattern =
        Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun isValidPackageName(packageName: String): Boolean =
        packageNamePattern.matches(packageName)

    fun forceStopCommand(packageName: String): String? {
        if (!isValidPackageName(packageName)) return null
        // Capture the force-stop status before printf replaces the last status.
        return "am force-stop $packageName; status=$DOLLAR?; printf '\\n$EXIT_STATUS_MARKER%s\\n' \"$DOLLAR" + "status\""
    }

    fun parseExitCode(output: String): Int? {
        val markerIndex = output.lastIndexOf(EXIT_STATUS_MARKER)
        if (markerIndex < 0) return null
        val status = output.substring(markerIndex + EXIT_STATUS_MARKER.length).trim()
        if (status.isEmpty() || status.any { !it.isDigit() }) return null
        return status.toIntOrNull()?.takeIf { it in 0..255 }
    }
}
