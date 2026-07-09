package com.privacyguard.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.privacyguard.utils.Constants
import com.privacyguard.utils.PreferencesManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (ImageProxy) -> Unit,
    private val preferencesManager: PreferencesManager
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var frameCount = AtomicLong(0)
    private var lastAnalysisTime = AtomicLong(0)
    private var isSuspiciousMode = false

    /** Set to true when multiple faces detected — increases FPS */
    fun setSuspiciousMode(suspicious: Boolean) {
        isSuspiciousMode = suspicious
    }

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(
                    Constants.CAMERA_RESOLUTION_WIDTH,
                    Constants.CAMERA_RESOLUTION_HEIGHT
                ))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val now = System.currentTimeMillis()
                val minInterval = if (isSuspiciousMode) {
                    Constants.ANALYSIS_MIN_INTERVAL_MS_SUSPICIOUS
                } else {
                    Constants.ANALYSIS_MIN_INTERVAL_MS_SAFE
                }

                // Frame rate throttle
                if (now - lastAnalysisTime.get() >= minInterval) {
                    lastAnalysisTime.set(now)
                    frameCount.incrementAndGet()
                    onFrame(imageProxy)
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
            } catch (_: Exception) {
                // Camera in use or unavailable
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        cameraProvider = null
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
    }
}
