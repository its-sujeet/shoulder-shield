package com.privacyguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.privacyguard.service.PrivacyGuardService
import com.privacyguard.system.PermissionManager
import com.privacyguard.system.ScreenLocker
import com.privacyguard.utils.Constants
import com.privacyguard.utils.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var permissionManager: PermissionManager
    private lateinit var preferencesManager: PreferencesManager
    private var isMonitoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionManager = PermissionManager(this)
        preferencesManager = PreferencesManager(this)

        findViewById<android.widget.Button>(R.id.btn_toggle).setOnClickListener {
            if (isMonitoring) {
                stopMonitoring()
            } else {
                startMonitoring()
            }
        }

        findViewById<android.widget.Button>(R.id.btn_settings).setOnClickListener {
            showSettingsDialog()
        }

        // Check permissions on start
        lifecycleScope.launch {
            isMonitoring = preferencesManager.isMonitoring.first()
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning from settings
        updateUI()
    }

    private fun startMonitoring() {
        if (!permissionManager.hasCameraPermission()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_camera_title)
                .setMessage(R.string.permission_camera_desc)
                .setPositiveButton("Grant") { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Request camera + notification
                        requestPermissions(
                            arrayOf(
                                android.Manifest.permission.CAMERA,
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ),
                            REQUEST_CAMERA_PERMISSION
                        )
                    } else {
                        requestPermissions(
                            arrayOf(android.Manifest.permission.CAMERA),
                            REQUEST_CAMERA_PERMISSION
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (!permissionManager.hasOverlayPermission()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_overlay_title)
                .setMessage(R.string.permission_overlay_desc)
                .setPositiveButton("Open Settings") { _, _ ->
                    permissionManager.openOverlaySettings()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (!permissionManager.hasNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
            return
        }

        // Device admin — skip if rooted, request if not
        val screenLocker = ScreenLocker(this)
        if (!screenLocker.isDeviceAdminActive() && !screenLocker.isDeviceRooted()) {
            AlertDialog.Builder(this)
                .setTitle("Device Admin Required")
                .setMessage("Privacy Guard needs device admin permission to auto-lock your screen when you walk away.")
                .setPositiveButton("Enable") { _, _ ->
                    startActivityForResult(screenLocker.requestAdmin(), ScreenLocker.REQUEST_ADMIN)
                }
                .setNegativeButton("Skip — no auto-lock", null)
                .show()
            return
        }

        // Start the foreground service
        val intent = Intent(this, PrivacyGuardService::class.java).apply {
            action = PrivacyGuardService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        isMonitoring = true
        updateUI()

        // Suggest battery optimization exemption
        if (!permissionManager.isBatteryOptimizationExempt()) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage("For best results, disable battery optimization for Privacy Guard.")
                .setPositiveButton("Disable") { _, _ ->
                    permissionManager.openBatteryOptimizationSettings()
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun stopMonitoring() {
        val intent = Intent(this, PrivacyGuardService::class.java).apply {
            action = PrivacyGuardService.ACTION_STOP
        }
        startService(intent)
        isMonitoring = false
        updateUI()
    }

    private fun updateUI() {
        val btn = findViewById<android.widget.Button>(R.id.btn_toggle)
        val statusText = findViewById<android.widget.TextView>(R.id.status_text)

        if (isMonitoring) {
            btn.text = getString(R.string.stop_monitoring)
            btn.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            statusText.text = "Privacy Shield: ACTIVE"
        } else {
            btn.text = getString(R.string.start_monitoring)
            btn.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            statusText.text = "Privacy Shield: INACTIVE"
        }

        // Permission indicator
        val permText = findViewById<android.widget.TextView>(R.id.permissions_text)
        val allGranted = permissionManager.allPermissionsGranted()
        permText.text = if (allGranted) "✓ All permissions granted"
            else "⚠ Permissions needed"
        permText.setTextColor(
            if (allGranted) ContextCompat.getColor(this, android.R.color.holo_green_light)
            else ContextCompat.getColor(this, android.R.color.holo_orange_light)
        )
    }

    private fun showSettingsDialog() {
        val timeoutOptions = arrayOf("3 seconds", "5 seconds", "10 seconds", "15 seconds", "30 seconds")
        val timeoutValues = arrayOf(3, 5, 10, 15, 30)

        lifecycleScope.launch {
            val currentTimeout = preferencesManager.awayTimeout.first()
            val currentIndex = timeoutValues.indexOf(currentTimeout).coerceAtLeast(0)
            val currentRooted = preferencesManager.isRooted.first()

            val items = arrayOf("Away timeout: ${timeoutOptions[currentIndex]}") +
                    if (currentRooted) arrayOf("✓ Device is rooted")
                    else arrayOf("Device is NOT rooted (enable for su lock)")

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Settings")
                .setItems(items) { dialog, which ->
                    when (which) {
                        0 -> showTimeoutPicker()
                        1 -> {
                            lifecycleScope.launch {
                                val newVal = !currentRooted
                                preferencesManager.setRooted(newVal)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (newVal) "Root mode ON — su will be used for lock"
                                    else "Root mode OFF — fallback lock only",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    dialog.dismiss()
                }
                .setPositiveButton("Done", null)
                .show()
        }
    }

    private fun showTimeoutPicker() {
        val timeoutOptions = arrayOf("3 seconds", "5 seconds", "10 seconds", "15 seconds", "30 seconds")
        val timeoutValues = arrayOf(3, 5, 10, 15, 30)

        lifecycleScope.launch {
            val currentTimeout = preferencesManager.awayTimeout.first()
            val currentIndex = timeoutValues.indexOf(currentTimeout).coerceAtLeast(0)

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Away Timeout")
                .setSingleChoiceItems(timeoutOptions, currentIndex) { dialog, which ->
                    lifecycleScope.launch {
                        preferencesManager.setAwayTimeout(timeoutValues[which])
                    }
                    dialog.dismiss()
                }
                .setPositiveButton("Done", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == 0) {
                Toast.makeText(this, "Camera granted", Toast.LENGTH_SHORT).show()
                startMonitoring()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] != 0) {
                Toast.makeText(this, "Notifications disabled — monitoring still works", Toast.LENGTH_LONG).show()
            }
            // Continue even if notification denied — feature degradation, not blocker
            startMonitoring()
        }
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenLocker.REQUEST_ADMIN) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Device admin enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Device admin denied — no auto-lock", Toast.LENGTH_LONG).show()
            }
            startMonitoring()
        }
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 100
        private const val REQUEST_NOTIFICATION_PERMISSION = 101
    }
}
