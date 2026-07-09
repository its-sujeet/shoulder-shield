package com.privacyguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "intrusions")
data class IntrusionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampMs: Long,
    val appPackage: String? = null,
    val appName: String? = null,
    val faceCount: Int = 0,
    val strangerSimilarity: Float = 0f,
    val confidenceScore: Int = 0,
    val triggerReason: String = "MULTI_FACE",
    val ghostSnapPath: String? = null,
    val dismissed: Boolean = false
)
