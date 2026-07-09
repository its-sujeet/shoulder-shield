package com.privacyguard.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.privacyguard.utils.Constants
import com.privacyguard.R
import com.privacyguard.engine.PrivacyState

class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var currentState: PrivacyState = PrivacyState.Normal

    fun handleStateChange(state: PrivacyState) {
        // Check overlay permission before any operation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                // Permission revoked — can't show overlay
                removeOverlay()
                currentState = state
                return
            }
        }

        when (state) {
            is PrivacyState.Normal,
            is PrivacyState.NoFacePending,
            is PrivacyState.MultipleFacesDetected -> removeOverlay()
            is PrivacyState.NoFaceWarning,
            is PrivacyState.NoFaceLocking -> showOverlay(R.layout.overlay_warning)
            is PrivacyState.MultipleFacesAlert -> showOverlay(R.layout.overlay_stranger)
        }
        currentState = state
    }

    fun getCurrentState(): PrivacyState = currentState

    private fun showOverlay(layoutRes: Int) {
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

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
                // View wasn't attached
            }
            overlayView = null
        }
    }

    fun destroy() {
        removeOverlay()
    }
}
