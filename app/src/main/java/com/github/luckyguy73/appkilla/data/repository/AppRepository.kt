package com.github.luckyguy73.appkilla.data.repository

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import com.github.luckyguy73.appkilla.adb.AdbConnectionState
import com.github.luckyguy73.appkilla.adb.AdbManager
import com.github.luckyguy73.appkilla.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result data class for force stop execution containing detailed diagnostic metrics.
 */
data class KillResult(
    val closedCount: Int,
    val freedMb: Int,
    val isShellConnected: Boolean,
    val connectionState: AdbConnectionState
)

/**
 * Repository for querying installed apps verified by a process snapshot, measuring
 * visible process memory, and executing shell-privileged force-stop commands.
 */
class AppRepository(
    private val context: Context,
    private val adbManager: AdbManager,
) {

    companion object {
        private const val TAG = "AppKilla_AppRepo"
        private const val KEY_FREED_MEMORY_MB = "freed_memory_mb"
    }

    private val prefs = context.getSharedPreferences("appkilla_stats", Context.MODE_PRIVATE)

    fun getSavedMemoryFreedMb(): Long {
        return prefs.getLong(KEY_FREED_MEMORY_MB, 0L)
    }

    private fun addFreedMemory(mb: Int) {
        val current = getSavedMemoryFreedMb()
        val newTotal = current + mb
        Log.d(TAG, "Updating saved freed memory: $current MB + $mb MB = $newTotal MB")
        prefs.edit { putLong(KEY_FREED_MEMORY_MB, newTotal) }
    }

    /**
     * Queries installed user-facing applications and includes only packages present
     * in the current process snapshot. A null shell snapshot falls back to Android's
     * restricted local process visibility; an empty snapshot means no matching apps.
     */
    suspend fun getApps(deviceProcessNames: Set<String>?): List<AppInfo> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Scanning installed applications and processes...")
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val selfPackage = context.packageName

        // Query launcher/leanback intents to identify user-facing apps
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }

        val launcherApps = pm.queryIntentActivities(mainIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
        val leanbackApps = pm.queryIntentActivities(leanbackIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()

        val allUserAppPackages = (launcherApps + leanbackApps).filter { it != selfPackage }.toSet()
        Log.d(TAG, "Discovered ${allUserAppPackages.size} launcher/leanback app packages.")

        // Android 11+ restricts ActivityManager process visibility for other apps.
        // Prefer the DADB shell process snapshot; otherwise use only
        // processes Android itself exposes. Never infer running state from install state.
        val localProcesses = am.runningAppProcesses ?: emptyList()
        Log.i(TAG, "ActivityManager exposed running processes: ${localProcesses.map { it.processName }}")
        val processNames = deviceProcessNames ?: localProcesses.map { it.processName }.toSet()
        val runningPids = localProcesses.associate { it.pid to it.processName }
        val runningPackageNames = processNames.map { it.substringBefore(":") }.toSet()
        Log.d(TAG, "Process snapshot: ${processNames.size} names, shell scan available=${deviceProcessNames != null}.")

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appList = mutableListOf<AppInfo>()

        for (appInfo in installedApps) {
            val pkgName = appInfo.packageName

            // Exclude self
            if (pkgName == selfPackage) continue

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUserFacing = allUserAppPackages.contains(pkgName)

            val isRunning = runningPackageNames.contains(pkgName)
            if (isRunning && (!isSystem || isUserFacing)) {
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (_: Exception) {
                    null
                }

                val memUsageMb = getProcessMemoryMb(am, runningPids, pkgName)

                appList.add(
                    AppInfo(
                        packageName = pkgName,
                        appName = label,
                        icon = icon,
                        memoryUsageMb = memUsageMb,
                        isSelected = false, // APPKILLA REQUIREMENTS: Default to unselected!
                        isSystemApp = isSystem,
                        isRunning = isRunning
                    )
                )
            }
        }

        val sortedList = appList.sortedByDescending { it.memoryUsageMb }
        Log.i(TAG, "Query completed. Loaded ${sortedList.size} candidate apps for task manager grid.")
        sortedList
    }

    private fun getProcessMemoryMb(
        am: ActivityManager,
        runningPids: Map<Int, String>,
        packageName: String
    ): Int {
        val pids = runningPids.filter {
            it.value == packageName || it.value.startsWith("$packageName:")
        }.keys.toIntArray()
        if (pids.isEmpty()) return 0

        val memInfoArray = am.getProcessMemoryInfo(pids)
        if (memInfoArray.isEmpty()) return 0

        var totalPssKb = 0
        for (memInfo in memInfoArray) {
            totalPssKb += memInfo.totalPss
        }
        val mb = totalPssKb / 1024
        Log.d(TAG, "Memory usage for package '$packageName' (PIDs ${pids.joinToString()}): $mb MB ($totalPssKb KB PSS)")
        return mb
    }

    /** Force-stops selected packages through the shell service and returns the results. */
    suspend fun killSelectedApps(appsToClose: List<AppInfo>): KillResult = withContext(Dispatchers.IO) {
        val connectionState = adbManager.connectionState.value
        val isShellConnected = connectionState is AdbConnectionState.Connected

        Log.i(TAG, "killSelectedApps triggered for ${appsToClose.size} apps. Shell access state: $connectionState")

        var totalFreed = 0
        var closedCount = 0

        for (app in appsToClose) {
            Log.i(TAG, "Processing force stop for app: '${app.appName}' (${app.packageName}), reported RAM: ${app.memoryUsageMb} MB")

            val adbSuccess = adbManager.forceStopApp(app.packageName)
            if (adbSuccess) {
                Log.i(TAG, "[SUCCESS] Shell force-stop succeeded for package '${app.packageName}'")
                closedCount++
                totalFreed += app.memoryUsageMb
            } else {
                Log.w(TAG, "[FAILED] Shell force-stop failed for package '${app.packageName}' (state: $connectionState)")
            }

        }

        if (totalFreed > 0) {
            addFreedMemory(totalFreed)
        }

        Log.i(TAG, "killSelectedApps completed: $closedCount/${appsToClose.size} force-stop commands succeeded, $totalFreed MB reported. Shell connected: $isShellConnected")
        KillResult(
            closedCount = closedCount,
            freedMb = totalFreed,
            isShellConnected = isShellConnected,
            connectionState = connectionState
        )
    }
}
