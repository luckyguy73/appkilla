package com.github.luckyguy73.appkilla.adb

/**
 * Parses Android's `ps -A` output. Process names may include a secondary
 * process suffix (for example, com.example.app:remote); callers can map those
 * back to their owning package.
 */
internal object RunningProcessParser {
    private val packageProcessPattern =
        Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+(?::[A-Za-z0-9_.]+)?")

    fun parse(output: String): Set<String>? {
        if (output.isBlank()) return null

        val processNames = linkedSetOf<String>()
        for (line in output.lineSequence()) {
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.isEmpty()) continue

            val candidate = columns.last()
            if (candidate.equals("NAME", ignoreCase = true) ||
                candidate.equals("CMD", ignoreCase = true) ||
                candidate.equals("COMMAND", ignoreCase = true)
            ) continue

            if (packageProcessPattern.matches(candidate)) {
                processNames += candidate
            }
        }

        // Non-empty output is a successful scan even when no app process names
        // match (e.g. only system processes are running).
        return processNames
    }
}
