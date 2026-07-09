package com.privacyguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.privacyguard.MainActivity
import com.privacyguard.R
import com.privacyguard.camera.CameraController
import com.privacyguard.data.AppDatabase
import com.privacyguard.data.IntrusionEntity
import com.privacyguard.engine.AppProtectionManager
import com.privacyguard.engine.ConfidenceEngine
import com.privacyguard.engine.PrivacyState
import com.privacyguard.engine.TrustManager
import com.privacyguard.ml.FaceDetectorManager
import com.privacyguard.overlay.OverlayManager
import com.privacyguard.system.ScreenLocker
import com.privacyguard.utils.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ShoulderShieldService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_START = "com.privacyguard.action.START"
        const val ACTION_STOP = "com.privacyguard.action.STOP"
        const val ACTION_PAUSE = "com.privacyguard.action.PAUSE"
        const val ACTION_RESUME = "com.privacyguard.action.RESUME"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "shoulder_shield_status"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ShoulderShieldService::class.java).apply {
                action = ACTION_START
            })
        }
        fun stop(context: Context) {
            context.startService(Intent(context, ShoulderShieldService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var prefs: PreferencesManager
    private lateinit var faceDetector: FaceDetectorManager
    private lateinit var cameraController: CameraController
    private lateinit var confidenceEngine: ConfidenceEngine
    private lateinit var trustManager: TrustManager
    private lateinit var appProtection: AppProtectionManager
    private lateinit var overlayManager: OverlayManager
    private lateinit var screenLocker: ScreenLocker
    private lateinit var db: AppDatabase

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var enrollmentFrames = 0
    private var enrollmentDone = false
    private var monitoring = false
    private var paused = false
    private var lastOwnerSeenMs = 0L
    private var shieldActive = false

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.markState(Lifecycle.State.CREATED)
        prefs = PreferencesManager(this)
        faceDetector = FaceDetectorManager(this)
        confidenceEngine = ConfidenceEngine()
        trustManager = TrustManager(this)
        appProtection = AppProtectionManager(this)
        overlayManager = OverlayManager(this)
        screenLocker = ScreenLocker(this)
        db = AppDatabase.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
            ACTION_PAUSE -> pauseMonitoring()
            ACTION_RESUME -> resumeMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        serviceScope.cancel()
        faceDetector.release()
        cameraController.release()
        overlayManager.destroy()
        lifecycleRegistry.markState(Lifecycle.State.DESTROYED)
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        lifecycleRegistry.markState(Lifecycle.State.STARTED)
        startForeground(NOTIFICATION_ID, note("Starting…"))

        serviceScope.launch {
            val saved = prefs.loadOwnerEmbedding()
            if (saved != null) {
                faceDetector.ownerEmbedding = saved
                enrollmentDone = true
                Log.d("Shield", "Loaded existing embedding")
            }
            prefs.setMonitoring(true)

            // Camera
            cameraController = CameraController(
                context = this@ShoulderShieldService,
                lifecycleOwner = this@ShoulderShieldService,
                onFrame = { imageProxy ->
                    faceDetector.processFrame(imageProxy, { faces ->
                        processFaces(faces)
                    }, { Log.e("Shield", "Frame error", it) })
                },
                preferencesManager = prefs
            )
            cameraController.start()
            lifecycleRegistry.markState(Lifecycle.State.RESUMED)
        }
    }

    private fun pauseMonitoring() {
        paused = true
        cameraController.stop()
        updateNote("Paused")
    }

    private fun resumeMonitoring() {
        if (!paused) return
        paused = false
        cameraController.start()
        updateNote("Resumed")
    }

    private fun stopMonitoring() {
        monitoring = false
        paused = false
        shieldActive = false
        cameraController.stop()
        overlayManager.destroy()
        serviceScope.launch { prefs.setMonitoring(false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        lifecycleRegistry.markState(Lifecycle.State.STARTED)
        stopSelf()
    }

    private fun processFaces(faces: List<FaceDetectorManager.RecognizedFace>) {
        if (paused) return
        val now = System.currentTimeMillis()

        // ─── Enrollment ───
        if (!enrollmentDone) {
            if (enrollmentFrames < FaceDetectorManager.ENROLLMENT_FRAMES_REQUIRED) {
                val rawFaces = faces.map { it.face }
                faceDetector.enrollFrame(rawFaces) { progress ->
                    val pct = (progress * 100).toInt()
                    updateNote("Enrolling… $pct%")
                }
                enrollmentFrames = faceDetector.enrollmentFrameCount
            }
            if (faceDetector.ownerEmbedding != null) {
                enrollmentDone = true
                serviceScope.launch {
                    prefs.saveOwnerEmbedding(faceDetector.ownerEmbedding!!)
                }
                updateNote("Ready")
            }
            return
        }

        // ─── Scoring ───
        val ownerPresent = faces.any { it.isOwner }
        val strangerCount = faces.count { !it.isOwner }
        if (ownerPresent) lastOwnerSeenMs = now
        val absenceS = if (ownerPresent) 0 else ((now - lastOwnerSeenMs) / 1000).toInt()

        val pkg = appProtection.getForegroundAppPackage()
        val isSensitive = pkg?.let { appProtection.getLevel(it) } == AppProtectionManager.Level.MAXIMUM
        val trusted = trustManager.isInTrustedEnvironment()

        val score = confidenceEngine.calculate(
            strangerCount = strangerCount,
            totalFaceCount = faces.size,
            ownerPresent = ownerPresent,
            absenceSeconds = absenceS,
            isDirectGaze = false,
            isSensitiveApp = isSensitive,
            isTrustedEnvironment = trusted
        )

        val action = confidenceEngine.getAction(score)
        Log.d("Shield", "Score=$score act=$action owner=$ownerPresent strangers=$strangerCount absent=${absenceS}s")

        // ─── Route ───
        when (action) {
            ConfidenceEngine.Action.NONE -> {
                if (shieldActive) { overlayManager.handleStateChange(PrivacyState.Normal); shieldActive = false }
                updateNote("You are present")
            }
            ConfidenceEngine.Action.NOTIFY -> {
                updateNote("⚠ Shoulder surfer")
            }
            ConfidenceEngine.Action.BLUR -> {
                if (!shieldActive) {
                    overlayManager.handleStateChange(PrivacyState.MultipleFacesAlert)
                    shieldActive = true
                }
                updateNote("🔒 Shield active")
            }
            ConfidenceEngine.Action.BIOMETRIC_SHIELD,
            ConfidenceEngine.Action.BIOMETRIC_REQUIRED -> {
                if (!shieldActive) {
                    overlayManager.handleStateChange(PrivacyState.MultipleFacesAlert)
                    shieldActive = true
                }
                updateNote("🔒 Scan fingerprint")
                if (strangerCount > 0) logIntrusion(strangerCount, score, action.name, faces)
            }
            ConfidenceEngine.Action.LOCK -> {
                logIntrusion(strangerCount, score, "LOCK", faces)
                overlayManager.handleStateChange(PrivacyState.Normal)
                shieldActive = false
                if (!screenLocker.lock()) {
                    overlayManager.handleStateChange(PrivacyState.NoFaceLocking)
                    shieldActive = true
                }
                updateNote("🔒 Locked")
            }
        }
    }

    private fun logIntrusion(
        count: Int, score: Int, reason: String,
        faces: List<FaceDetectorManager.RecognizedFace>
    ) {
        serviceScope.launch {
            try {
                val pkg = appProtection.getForegroundAppPackage()
                db.intrusionDao().insert(IntrusionEntity(
                    timestampMs = System.currentTimeMillis(),
                    appPackage = pkg,
                    appName = pkg?.let { appProtection.getAppName(it) },
                    faceCount = faces.size,
                    strangerSimilarity = faces.maxOfOrNull { it.similarity } ?: 0f,
                    confidenceScore = score,
                    triggerReason = reason
                ))
            } catch (_: Exception) {}
        }
    }

    // ─── Notification ───
    private fun createNotificationChannel() {
        val c = NotificationChannel(CHANNEL_ID, "Shield Status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Current protection status"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(c)
    }

    private fun note(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shoulder Shield")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true).build()
    }

    private fun updateNote(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, note(text))
    }
}
