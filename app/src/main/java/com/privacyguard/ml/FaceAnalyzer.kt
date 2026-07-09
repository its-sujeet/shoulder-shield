package com.privacyguard.ml

import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

data class FaceAnalysisResult(
    val faceCount: Int,
    val faces: List<FaceData>,
    val timestampMs: Long
)

data class FaceData(
    val boundingBox: Rect,
    val trackingId: Int?,
    val headEulerY: Float,  // yaw — looking left/right
    val headEulerZ: Float,  // roll — tilted head
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val smilingProbability: Float?,
    val faceSizeRatio: Float,  // relative to frame area
    val confidence: Float = 1f
) {
    fun isLikelyLookingAtScreen(): Boolean {
        // If head is turned too far (>30° yaw), they're looking away
        return kotlin.math.abs(headEulerY) < 30f
    }

    fun isSmall(sizeThreshold: Float): Boolean {
        return faceSizeRatio < sizeThreshold
    }
}

class FaceAnalyzer {

    fun analyze(faces: List<Face>, frameWidth: Int, frameHeight: Int, timestampMs: Long): FaceAnalysisResult {
        val frameArea = frameWidth * frameHeight
        val faceDataList = faces.map { face ->
            val box = face.boundingBox
            val faceSize = box.width() * box.height()
            val sizeRatio = if (frameArea > 0) faceSize.toFloat() / frameArea else 0f

            FaceData(
                boundingBox = box,
                trackingId = face.trackingId,
                headEulerY = face.headEulerAngleY,
                headEulerZ = face.headEulerAngleZ,
                leftEyeOpenProbability = face.leftEyeOpenProbability,
                rightEyeOpenProbability = face.rightEyeOpenProbability,
                smilingProbability = face.smilingProbability,
                faceSizeRatio = sizeRatio
            )
        }

        return FaceAnalysisResult(
            faceCount = faces.size,
            faces = faceDataList,
            timestampMs = timestampMs
        )
    }

    /** Filter out tiny, low-confidence faces that are likely false positives */
    fun filterValidFaces(result: FaceAnalysisResult, minSizeRatio: Float = 0.015f): List<FaceData> {
        return result.faces.filter { !it.isSmall(minSizeRatio) }
    }

    /** Find which face is likely the primary user (largest, centered, looking at screen) */
    fun findPrimaryFace(faces: List<FaceData>): FaceData? {
        if (faces.isEmpty()) return null
        if (faces.size == 1) return faces.first()

        // Score by: size (weight 3), centered-ness (weight 2), looking at screen (weight 5)
        return faces.maxByOrNull { face ->
            var score = face.faceSizeRatio * 3f
            // Prefer faces looking at screen
            if (face.isLikelyLookingAtScreen()) score += 5f
            score
        }
    }
}
