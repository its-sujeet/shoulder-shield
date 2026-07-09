package com.privacyguard.engine

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Maps installed apps to protection levels.
 *
 * Level              | Behavior
 * -------------------|---------
 * OFF                | Ignore — never trigger for this app
 * NOTIFY_ONLY        | Quiet notification, no overlay
 * MEDIUM             | Blur overlay on high confidence
 * HIGH               | Shield + biometric prompt
 * MAXIMUM            | Instant lock, no grace period
 */
class AppProtectionManager(private val context: Context) {

    enum class Level(val value: Int) {
        OFF(0),
        NOTIFY_ONLY(1),
        MEDIUM(2),
        HIGH(3),
        MAXIMUM(4)
    }

    private val customLevels = mutableMapOf<String, Level>()

    /** Well-known sensitive apps auto-assigned MAXIMUM unless user overrides. */
    private val sensitivePackagePatterns = listOf(
        "com.google.android.apps.wallet",
        "com.google.android.apps.googlewallet",
        "com.samsung.android.spay",
        "com.paypal.merchant",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.banking",
        "com.chase.sig",
        "com.wf.wellsfargo",
        "com.bankofamerica",
        "com.citi.citimobile",
        "com.usaa",
        "co.banking.android",
        "com.axis.mi",
        "com.snapchat.android",
        "com.whatsapp",
    )

    /** Set custom level for a specific package. */
    fun setLevel(packageName: String, level: Level) {
        if (level == getDefaultLevel(packageName)) {
            customLevels.remove(packageName)
        } else {
            customLevels[packageName] = level
        }
    }

    /** Get all custom overrides. */
    fun getCustomLevels(): Map<String, Level> = customLevels.toMap()

    /** Get the effective level for a package (custom override or default). */
    fun getLevel(packageName: String): Level {
        return customLevels[packageName] ?: getDefaultLevel(packageName)
    }

    /** Get default level based on known sensitivity patterns. */
    private fun getDefaultLevel(packageName: String): Level {
        return when {
            sensitivePackagePatterns.any { packageName.startsWith(it) } -> Level.MAXIMUM
            else -> Level.MEDIUM
        }
    }

    /** Detect the current foreground app package. */
    fun getForegroundAppPackage(): String? {
        // Try UsageStatsManager first (requires PACKAGE_USAGE_STATS permission)
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 1000 * 60 * 2, // last 2 minutes
                    now
                )
                val sorted = stats
                    ?.filter { it.packageName.isNotEmpty() }
                    ?.sortedByDescending { it.lastTimeUsed }
                sorted?.firstOrNull()?.packageName
            } else null
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve a package name to a human-readable app name. */
    fun getAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    /** List all installed apps with their current protection level. */
    fun getAllInstalledApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(intent, 0)
        val seen = mutableSetOf<String>()
        val entries = mutableListOf<AppEntry>()

        for (resolveInfo in activities) {
            val pkg = resolveInfo.activityInfo?.packageName ?: continue
            if (pkg in seen) continue
            seen.add(pkg)
            entries.add(
                AppEntry(
                    packageName = pkg,
                    appName = getAppName(pkg),
                    level = getLevel(pkg)
                )
            )
        }

        return entries.sortedBy { it.appName }
    }

    data class AppEntry(
        val packageName: String,
        val appName: String,
        val level: Level
    )
}
