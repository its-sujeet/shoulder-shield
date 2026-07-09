package com.privacyguard.system

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.privacyguard.utils.PreferencesManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class ScreenLocker(private val context: Context) {

    private val policyManager: DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
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
     * Priority: device admin → su shell → false (overlay-only fallback)
     */
    fun lock(): Boolean {
        return try {
            if (isDeviceAdminActive()) {
                policyManager?.lockNow()
                true
            } else if (runAsRoot("input keyevent 26")) {
                true
            } else {
                false // overlay-only fallback
            }
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Soft lock: first attempts biometric verification. If the user authenticates
     * successfully, no lock is applied (they're trusted). If biometric fails
     * (error or too many failures), falls back to [lock].
     *
     * @param context A [Context] used for capability checking (biometric check).
     * @param onSoftUnlock Called if the user authenticates and no lock is needed.
     * @param onHardLock Called if biometric fails and [lock] is invoked as fallback.
     */
    fun softLock(
        context: Context,
        onSoftUnlock: () -> Unit = {},
        onHardLock: () -> Unit = {}
    ) {
        if (!BiometricHelper.canAuthenticate(context)) {
            // No biometric capability — go straight to lock
            lock()
            onHardLock()
            return
        }

        // The caller is expected to use a FragmentActivity context for the prompt.
        // If the context is not a FragmentActivity, fall straight to lock.
        val activity = (context as? androidx.fragment.app.FragmentActivity)
        if (activity == null) {
            lock()
            onHardLock()
            return
        }

        BiometricHelper.authenticate(
            activity = activity,
            title = "Quick Verification",
            subtitle = "Verify your identity to avoid full device lock",
            onSuccess = {
                // User authenticated — don't lock
                onSoftUnlock()
            },
            onError = { _, err ->
                // Non-recoverable error — lock as fallback
                lock()
                onHardLock()
            },
            onFailed = {
                // Failed attempt — lock as fallback
                lock()
                onHardLock()
            }
        )
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
