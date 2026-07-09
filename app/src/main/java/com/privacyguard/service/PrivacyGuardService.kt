package com.privacyguard.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.privacyguard.utils.Constants
import com.privacyguard.PrivacyGuardApp
import com.privacyguard.R
import com.privacyguard.camera.CameraController
import com.privacyguard.engine.PrivacyDecisionEngine
import com.privacyguard.engine.PrivacyState
import com.privacyguard.ml.FaceAnalyzer
import com.privacyguard.ml.FaceDetectorManager
import com.privacyguard.overlay.OverlayManager
import com.privacyguard.system.PermissionManager
import com.privacyguard.system.ScreenLocker
import com.privacyguard.utils.PreferencesManager
import kotlinx.coroutines.*

class PrivacyGuardService : LifecycleService() {

    private lateinit var cameraController: CameraController
    private lateinit var faceDetectorManager: FaceDetectorManager
    private lateinit var faceAnalyzer: FaceAnalyzer
    private lateinit var decisionEngine: PrivacyDecisionEngine
    private lateinit var overlayManager: OverlayManager
    private lateinit var screenLocker: ScreenLocker
    private lateinit var permissionManager: PermissionManager
    private lateinit var preferencesManager: PreferencesManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lockCheckJob: Job? = null
    private var stickyLockCooldownUntil: Long = 0L
    private var lastState: PrivacyState = PrivacyState.Normal

    private var isScreenOn = true
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> isScreenOn = true
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    overlayManager.destroy()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        permissionManager = PermissionManager(this)
        preferencesManager = PreferencesManager(this)
        overlayManager = OverlayManager(this)
        screenLocker = ScreenLocker(this)
        faceAnalyzer = FaceAnalyzer()
        decisionEngine = PrivacyDecisionEngine(
            noFaceGraceMs = Constants.NO_FACE_GRACE_MS,
            noFaceWarningMs = Constants.NO_FACE_WARNING_MS,
            multiFaceDebounceFrames = Constants.MULTI_FACE_DEBOUNCE_FRAMES,
            multiFaceHoldFrames = Constants.MULTI_FACE_HOLD_FRAMES,
            noFaceRequiredFrames = Constants.NO_FACE_REQUIRED_FRAMES
        )
        faceDetectorManager = FaceDetectorManager()

        cameraController = CameraController(
            context = this,
            lifecycleOwner = this,
            onFrame = { imageProxy ->
                faceDetectorManager.processFrame(
                    imageProxy = imageProxy,
                    onResult = { faces ->
                        val now = System.currentTimeMillis()
                        val result = faceAnalyzer.analyze(
                            faces = faces,
                            frameWidth = Constants.CAMERA_RESOLUTION_WIDTH,
                            frameHeight = Constants.CAMERA_RESOLUTION_HEIGHT,
                            timestampMs = now
                        )
                        val validFaces = faceAnalyzer.filterValidFaces(result)
                        val primaryFace = faceAnalyzer.findPrimaryFace(validFaces)

                        // FPS throttle: higher when >1 face
                        cameraController.setSuspiciousMode(validFaces.size > 1)

                        // Owner detection placeholder: single face = owner
                        val isOwner = validFaces.size == 1

                        val newState = decisionEngine.process(
                            faceCount = validFaces.size,
                            isOwner = isOwner,
                            nowMs = now
                        )

                        if (newState != lastState) {
                            lastState = newState
                            broadcastState(newState, validFaces.size, isOwner)
                        }

                        handleStateOnMainThread(newState, now)
                    },
                    onError = { /* log */ }
                )
            },
            preferencesManager = preferencesManager
        )

        // Screen on/off receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        // Polling loop — checks lock state every 500ms
        lockCheckJob = serviceScope.launch {
            while (isActive) {
                delay(500)
                if (!isScreenOn) continue
                val now = System.currentTimeMillis()
                if (decisionEngine.shouldLock(now)) {
                    overlayManager.handleStateChange(PrivacyState.NoFaceLocking)
                    if (now > stickyLockCooldownUntil) {
                        val locked = screenLocker.lock()
                        if (locked) stickyLockCooldownUntil = now + Constants.LOCK_COOLDOWN_MS
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                if (!permissionManager.allPermissionsGranted()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                serviceScope.launch {
                    preferencesManager.setMonitoring(true)
                    val timeoutMs = preferencesManager.getAwayTimeoutMs()
                    // Split user timeout into grace (80%) and warning (20%)
                    val graceMs = (timeoutMs * 0.8).toLong()
                    val warningMs = timeoutMs - graceMs
                    decisionEngine.setDynamicTimeout(graceMs, warningMs)
                }
                cameraController.start()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // OS-recreated service with null intent — don't restart camera
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lockCheckJob?.cancel()
        serviceScope.launch { preferencesManager.setMonitoring(false) }
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        cameraController.release()
        faceDetectorManager.release()
        overlayManager.destroy()
        decisionEngine.reset()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun handleStateOnMainThread(state: PrivacyState, nowMs: Long) {
        serviceScope.launch(Dispatchers.Main) {
            overlayManager.handleStateChange(state)
            updateNotification(state)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, PrivacyGuardService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PrivacyGuardApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Privacy Guard Active")
            .setContentText("Monitoring for shoulder surfers")
            .setSmallIcon(android.R.drawable.ic_menu_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(state: PrivacyState) {
        val text = when (state) {
            is PrivacyState.Normal -> "You are present — safe"
            is PrivacyState.NoFacePending -> "Looking for you..."
            is PrivacyState.NoFaceWarning -> "You walked away — shielding"
            is PrivacyState.NoFaceLocking -> "Locking screen..."
            is PrivacyState.MultipleFacesDetected -> "Checking..."
            is PrivacyState.MultipleFacesAlert -> "Stranger detected — shielded"
        }

        val notification = NotificationCompat.Builder(this, PrivacyGuardApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Privacy Guard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastState(state: PrivacyState, faceCount: Int, isOwner: Boolean) {
        val intent = Intent(Constants.ACTION_UPDATE_STATE).apply {
            putExtra(Constants.EXTRA_STATE, state::class.qualifiedName)
            putExtra(Constants.EXTRA_FACE_COUNT, faceCount)
            putExtra(Constants.EXTRA_IS_OWNER, isOwner)
        }
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_START = "com.privacyguard.START"
        const val ACTION_STOP = "com.privacyguard.STOP"
        private const val NOTIFICATION_ID = 1001
    }
}
