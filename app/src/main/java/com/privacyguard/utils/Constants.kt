package com.privacyguard.utils

object Constants {
    // Camera
    const val CAMERA_RESOLUTION_WIDTH = 640
    const val CAMERA_RESOLUTION_HEIGHT = 480
    const val ANALYSIS_MIN_INTERVAL_MS_SAFE = 500L     // ~2 FPS
    const val ANALYSIS_MIN_INTERVAL_MS_SUSPICIOUS = 200L // ~5 FPS

    // Decision engine — frame-count based
    const val NO_FACE_GRACE_MS = 3000L
    const val NO_FACE_WARNING_MS = 2000L
    const val MULTI_FACE_DEBOUNCE_FRAMES = 3
    const val MULTI_FACE_HOLD_FRAMES = 6
    const val NO_FACE_REQUIRED_FRAMES = 10

    // Lock
    const val LOCK_COOLDOWN_MS = 5000L

    // Face detection
    const val MIN_FACE_SIZE_RATIO = 0.015f
    const val ENROLLMENT_SAMPLES = 15

    // Overlay
    const val OVERLAY_FLAG_DIM_AMOUNT = 0.85f

    // Preferences
    const val PREF_AWAY_TIMEOUT = "away_timeout"
    const val PREF_AUTOSTART = "autostart"
    const val PREF_IS_MONITORING = "is_monitoring"
    const val PREF_ENROLLMENT_DATA = "enrollment_data"
    const val PREF_IS_ENROLLED = "is_enrolled"
    const val PREF_IS_ROOTED = "is_rooted"

    // Service actions
    const val ACTION_START = "com.privacyguard.START"
    const val ACTION_STOP = "com.privacyguard.STOP"
    const val ACTION_UPDATE_STATE = "com.privacyguard.UPDATE_STATE"

    // Broadcast extras
    const val EXTRA_STATE = "privacy_state"
    const val EXTRA_FACE_COUNT = "face_count"
    const val EXTRA_IS_OWNER = "is_owner"
}
