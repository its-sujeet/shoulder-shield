package com.privacyguard.system

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.privacyguard.R

/**
 * A transparent activity that briefly shows when we need to draw
 * the overlay from a background context. Shows the overlay activity
 * with a secure flag so it sits on top of everything.
 */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        window?.apply {
            setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE or
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                android.view.WindowManager.LayoutParams.FLAG_SECURE or
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val action = intent?.action
        when (action) {
            ACTION_OVERLAY -> {
                // This is a trampoline — just ensures we have a foreground context
                finish()
            }
        }
    }

    companion object {
        const val ACTION_OVERLAY = "com.privacyguard.SHOW_OVERLAY"
    }
}
