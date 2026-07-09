package com.privacyguard.system

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.privacyguard.utils.PreferencesManager
import kotlinx.coroutines.runBlocking

class ScreenLocker(private val context: Context) {

    private val policyManager: DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val preferencesManager = PreferencesManager(context)

    companion object {
        const val REQUEST_ADMIN = 200
    }

    /** Check user-declared flag first, fall back to binary probes for auto-detect. */
    fun isDeviceRooted(): Boolean {
        // Read fresh each call — user may toggle the setting
        val userFlag = runBlocking {
            try { preferencesManager.isRooted.first() } catch (_: Exception) { false }
        }
        if (userFlag) return true
        // Binary probes (Magisk, SuperSU, etc.)
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su"
        )
        for (p in paths) {
            if (java.io.File(p).exists()) return true
        }
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "true"))
            proc.waitFor()
            proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Try to execute a command via su. Returns true if it ran without error. */
    private fun runAsRoot(command: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            proc.waitFor()
            proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Lock the device. Returns true if lock was triggered.
     * Never throws — all exceptions caught internally so the coroutine survives.
     *
     * Priority: device admin → su shell → goToSleep (pre-P) → overlay fallback
     */
    fun lock(): Boolean {
        return try {
            if (isDeviceAdminActive()) {
                policyManager?.lockNow()
                true
            } else if (runAsRoot("input keyevent 26")) {
                true
            } else {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    powerManager.goToSleep(System.currentTimeMillis())
                    true
                } else {
                    false
                }
            }
        } catch (_: SecurityException) {
            false
        }
    }

    fun isDeviceAdminActive(): Boolean {
        return policyManager?.isAdminActive(componentName) == true
    }

    fun requestAdmin(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Privacy Guard needs device admin to lock your screen when you walk away."
            )
        }
    }

    class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver()
}
