package com.privacyguard.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.privacyguard.service.PrivacyGuardService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start service on boot — it will check prefs and decide if monitoring should resume
            val serviceIntent = Intent(context, PrivacyGuardService::class.java).apply {
                action = PrivacyGuardService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
