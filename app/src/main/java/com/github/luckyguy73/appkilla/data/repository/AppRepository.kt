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
    val isAdbConnected: Boolean,
    val adbState: AdbConnectionState
)

/**
 * Service/Repository responsible for querying installed and running applications,
 * memory usage statistics, and executing force-stop commands.
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
        return prefs.getLong(KEY_FREED_MEMORY_MB, 1400L)
    }

    private fun addFreedMemory(mb: Int) {
        val current = getSavedMemoryFreedMb()
        val newTotal = current + mb
        Log.d(TAG, "Updating saved freed memory: $current MB + $mb MB = $newTotal MB")
        prefs.edit { putLong(KEY_FREED_MEMORY_MB, newTotal) }
    }

    /**
     * Queries installed user-facing applications and includes only packages present
     * in the current process snapshot. A null ADB snapshot falls back to Android's
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
        // Prefer the device-wide ADB process snapshot; use Android's visible process
        // list only when the ADB scan itself was unavailable.
        val localProcesses = am.runningAppProcesses ?: emptyList()
        val processNames = deviceProcessNames ?: localProcesses.map { it.processName }.toSet()
        val runningPids = localProcesses.associate { it.pid to it.processName }
        val runningPackageNames = processNames.map { it.substringBefore(":") }.toSet()
        Log.d(TAG, "Process snapshot: ${processNames.size} names, ADB scan available=${deviceProcessNames != null}.")

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appList = mutableListOf<AppInfo>()

        for (appInfo in installedApps) {
            val pkgName = appInfo.packageName

            // Exclude self
            if (pkgName == selfPackage) continue

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUserFacing = allUserAppPackages.contains(pkgName)

            // Only show installed apps with a process present in this scan. This
            // prevents the grid from presenting every installed app as "running".
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

    /**
     * Force-stops all selected apps via ADB shell `am force-stop`.
     * Also attempts `ActivityManager.killBackgroundProcesses`.
     * Logs detailed force-stop status per package and returns a [KillResult].
     */
    suspend fun killSelectedApps(appsToClose: List<AppInfo>): KillResult = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val adbState = adbManager.connectionState.value
        val isAdbConnected = adbState is AdbConnectionState.Connected

        Log.i(TAG, "killSelectedApps triggered for ${appsToClose.size} apps. ADB Connection State: $adbState")

        var totalFreed = 0
        var closedCount = 0

        for (app in appsToClose) {
            Log.i(TAG, "Processing force stop for app: '${app.appName}' (${app.packageName}), reported RAM: ${app.memoryUsageMb} MB")

            // 1. Try local ADB force-stop
            val adbSuccess = adbManager.forceStopApp(app.packageName)
            if (adbSuccess) {
                Log.i(TAG, "[SUCCESS] Local ADB force-stop succeeded for package '${app.packageName}'")
                closedCount++
                totalFreed += app.memoryUsageMb
            } else {
                Log.w(TAG, "[FAILED] Local ADB force-stop failed or ADB not connected for package '${app.packageName}' (ADB State: $adbState)")
            }

            // 2. Local fallback kill (background process trim)
            try {
                Log.d(TAG, "Invoking ActivityManager.killBackgroundProcesses for package '${app.packageName}'...")
                am.killBackgroundProcesses(app.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Exception calling killBackgroundProcesses for '${app.packageName}'", e)
            }
        }

        if (totalFreed > 0) {
            addFreedMemory(totalFreed)
        }

        Log.i(TAG, "killSelectedApps completed: $closedCount/${appsToClose.size} apps closed, $totalFreed MB freed. ADB Connected: $isAdbConnected")
        KillResult(
            closedCount = closedCount,
            freedMb = totalFreed,
            isAdbConnected = isAdbConnected,
            adbState = adbState
        )
    }
}