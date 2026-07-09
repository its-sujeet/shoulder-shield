package com.privacyguard.ml

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

class FaceDetectorManager {

    private var detector: FaceDetector? = null
    private val isProcessing = AtomicBoolean(false)

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(options)
    }

    @OptIn(ExperimentalGetImage::class)
    fun processFrame(
        imageProxy: ImageProxy,
        onResult: (List<Face>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Atomic gate — one frame at a time
        if (isProcessing.getAndSet(true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector?.process(inputImage)
            ?.addOnSuccessListener { faces -> onResult(faces) }
            ?.addOnFailureListener { e -> onError(e) }
            ?.addOnCompleteListener {
                isProcessing.set(false)
                if (imageProxy.image?.isClosed != true) imageProxy.close()
            }
    }

    fun processBitmap(
        bitmap: Bitmap,
        rotation: Int = 0,
        onResult: (List<Face>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val inputImage = InputImage.fromBitmap(bitmap, rotation)
        detector?.process(inputImage)
            ?.addOnSuccessListener { faces -> onResult(faces) }
            ?.addOnFailureListener { e -> onError(e) }
    }

    fun release() {
        detector?.close()
        detector = null
    }
}
