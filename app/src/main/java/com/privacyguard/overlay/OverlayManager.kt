package com.privacyguard.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.privacyguard.R
import com.privacyguard.engine.PrivacyState
import com.privacyguard.system.BiometricHelper
import com.privacyguard.system.ScreenLocker
import com.privacyguard.utils.Constants

/**
 * Manages privacy shield overlays that obscure the screen when a threat is detected.
 * Supports blur overlays (via layout resources) and decoy overlays (programmatic views),
 * as well as biometric-gated dismiss and soft-lock flows.
 */
class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var currentState: PrivacyState = PrivacyState.Normal

    private val decoyManager = DecoyScreenManager(context)

    // ──────────────────────────────────────────────
    // State management
    // ──────────────────────────────────────────────

    fun handleStateChange(state: PrivacyState) {
        // Check overlay permission before any operation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                // Permission revoked — can't show overlay
                removeOverlay()
                decoyManager.dismiss()
                currentState = state
                return
            }
        }

        when (state) {
            is PrivacyState.Normal,
            is PrivacyState.NoFacePending,
            is PrivacyState.MultipleFacesDetected -> {
                removeOverlay()
                decoyManager.dismiss()
            }
            is PrivacyState.NoFaceWarning,
            is PrivacyState.NoFaceLocking -> showOverlay(R.layout.overlay_warning)
            is PrivacyState.MultipleFacesAlert -> showOverlay(R.layout.overlay_stranger)
        }
        currentState = state
    }

    fun getCurrentState(): PrivacyState = currentState

    // ──────────────────────────────────────────────
    // Privacy shield overlays (blur / semi-transparent)
    // ──────────────────────────────────────────────

    private fun showOverlay(layoutRes: Int) {
        // If a decoy is showing, don't replace it
        if (decoyManager.isShowing()) return
        if (overlayView != null) return

        val view = LayoutInflater.from(context).inflate(layoutRes, null, false)
        overlayView = view

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply { dimAmount = Constants.OVERLAY_FLAG_DIM_AMOUNT }

        try {
            windowManager.addView(view, params)
        } catch (_: SecurityException) {
            // Overlay permission was revoked between check and add
            overlayView = null
        }
    }

    fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
                // View wasn't attached
            }
            overlayView = null
        }
    }

    // ──────────────────────────────────────────────
    // Decoy screen (fake app overlays)
    // ──────────────────────────────────────────────

    /**
     * Show a decoy overlay of the given type instead of the plain blur overlay.
     * This makes the shield less obvious to onlookers.
     *
     * @param type The decoy type: 0 = CALCULATOR, 1 = NOTES, 2 = WEATHER
     */
    fun showDecoyScreen(type: Int) {
        val decoyType = when (type) {
            0 -> DecoyScreenManager.DecoyType.CALCULATOR
            1 -> DecoyScreenManager.DecoyType.NOTES
            else -> DecoyScreenManager.DecoyType.WEATHER
        }
        // Remove any standard overlay first
        removeOverlay()
        decoyManager.showDecoy(context, decoyType)
    }

    fun dismissDecoy() {
        decoyManager.dismiss()
    }

    // ──────────────────────────────────────────────
    // Biometric prompt integration
    // ──────────────────────────────────────────────

    /**
     * Show a biometric prompt when a privacy shield is active.
     * The caller must provide a [FragmentActivity] context.
     */
    fun showBiometricPrompt() {
        if (!BiometricHelper.canAuthenticate(context)) return

        val activity = context as? androidx.fragment.app.FragmentActivity ?: return

        BiometricHelper.authenticate(
            activity = activity,
            title = "Privacy Guard",
            subtitle = "Authenticate to dismiss shield",
            onSuccess = {
                handleAuthenticationResult(true)
            },
            onError = { _, _ ->
                handleAuthenticationResult(false)
            },
            onFailed = {
                handleAuthenticationResult(false)
            }
        )
    }

    /**
     * Show a privacy shield *and* immediately trigger a biometric prompt.
     * If biometric succeeds the shield is dismissed; if it fails the shield
     * stays and the device may lock.
     *
     * @param context Application or Activity context.
     * @param biometricHelperClass Fully-qualified class name (informational; actual
     *                             helper usage is via [BiometricHelper] directly).
     */
    fun showPrivacyShieldWithBiometric(
        context: Context,
        @Suppress("UNUSED_PARAMETER") biometricHelperClass: String
    ) {
        // Show the appropriate overlay based on current state
        when (currentState) {
            is PrivacyState.MultipleFacesAlert -> showOverlay(R.layout.overlay_stranger)
            is PrivacyState.NoFaceWarning,
            is PrivacyState.NoFaceLocking -> showOverlay(R.layout.overlay_warning)
            else -> showOverlay(R.layout.overlay_stranger)
        }

        showBiometricPrompt()
    }

    /**
     * Dismiss the current overlay after authenticating via biometrics.
     * If the user authenticates successfully, shield is removed.
     * On failure, the device is locked via [ScreenLocker].
     *
     * @param onDismiss Called when authentication succeeds and shield is dismissed.
     */
    fun dismissWithAuthentication(onDismiss: () -> Unit) {
        if (!BiometricHelper.canAuthenticate(context)) {
            // No biometric — just dismiss
            removeOverlay()
            decoyManager.dismiss()
            onDismiss()
            return
        }

        val activity = context as? androidx.fragment.app.FragmentActivity
        if (activity == null) {
            removeOverlay()
            decoyManager.dismiss()
            onDismiss()
            return
        }

        BiometricHelper.authenticate(
            activity = activity,
            title = "Privacy Guard",
            subtitle = "Authenticate to dismiss shield",
            onSuccess = {
                removeOverlay()
                decoyManager.dismiss()
                onDismiss()
            },
            onError = { _, _ ->
                ScreenLocker(context).lock()
            },
            onFailed = {
                ScreenLocker(context).lock()
            }
        )
    }

    /**
     * Handle the result of a biometric authentication attempt.
     *
     * @param success true if authentication succeeded (dismiss overlay),
     *                false if it failed (lock device).
     */
    fun handleAuthenticationResult(success: Boolean) {
        if (success) {
            removeOverlay()
            decoyManager.dismiss()
        } else {
            ScreenLocker(context).lock()
        }
    }

    // ──────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────

    fun destroy() {
        removeOverlay()
        decoyManager.destroy()
    }
}
