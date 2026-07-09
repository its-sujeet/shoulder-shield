package com.privacyguard.engine

sealed class PrivacyState {
    data object Normal : PrivacyState()
    data object NoFacePending : PrivacyState()
    data object NoFaceWarning : PrivacyState()
    data object NoFaceLocking : PrivacyState()
    data object MultipleFacesDetected : PrivacyState()
    data object MultipleFacesAlert : PrivacyState()
}

class PrivacyDecisionEngine(
    private var noFaceGraceMs: Long = 3000L,
    private var noFaceWarningMs: Long = 2000L,
    private val multiFaceDebounceFrames: Int = 3,
    private val multiFaceHoldFrames: Int = 6,
    private val noFaceRequiredFrames: Int = 10
) {
    private var currentState: PrivacyState = PrivacyState.Normal
    private var noFaceTimerStart: Long = 0L
    private var multiFaceFirstFrameTime: Long = 0L
    private var multiFaceConfirmFrameCount: Int = 0
    private var multiFaceHoldFrameCount: Int = 0
    private var noFaceConsecutiveFrames: Int = 0
    private var noFaceTimerActive = false
    private var lockCooldownUntil: Long = 0L

    fun getCurrentState(): PrivacyState = currentState

    /** Update per-user timeout setting. Called once on start, not per frame. */
    fun setDynamicTimeout(graceMs: Long, warningMs: Long) {
        // Reflects values from both the constructor default and PreferencesManager
        this.noFaceGraceMs = graceMs
        this.noFaceWarningMs = warningMs
    }

    /** Feed face analysis result per frame. Returns the new state. */
    fun process(faceCount: Int, isOwner: Boolean, nowMs: Long): PrivacyState {
        val prevState = currentState

        // ─── PRIORITY 1: Multiple faces = immediate privacy threat ───
        if (faceCount >= 2 || (faceCount == 1 && !isOwner)) {
            // Cancel any no-face timer immediately
            noFaceTimerActive = false
            noFaceConsecutiveFrames = 0

            // Frame-count-based debounce
            if (multiFaceConfirmFrameCount == 0) {
                multiFaceFirstFrameTime = nowMs
            }
            multiFaceConfirmFrameCount++
            multiFaceHoldFrameCount = 0

            currentState = if (multiFaceConfirmFrameCount >= multiFaceDebounceFrames) {
                PrivacyState.MultipleFacesAlert
            } else {
                PrivacyState.MultipleFacesDetected
            }
            return currentState
        }

        // ─── PRIORITY 2: No face detected ───
        if (faceCount == 0) {
            // Reset multi-face counters
            multiFaceConfirmFrameCount = 0

            noFaceConsecutiveFrames++

            // Start timer on first frame that meets the threshold
            if (noFaceConsecutiveFrames >= noFaceRequiredFrames && !noFaceTimerActive) {
                noFaceTimerStart = nowMs
                noFaceTimerActive = true
            }

            val elapsed = if (noFaceTimerActive) nowMs - noFaceTimerStart else 0L

            currentState = when {
                !noFaceTimerActive -> PrivacyState.Normal
                elapsed < noFaceGraceMs -> PrivacyState.NoFacePending
                elapsed < noFaceGraceMs + noFaceWarningMs -> PrivacyState.NoFaceWarning
                else -> PrivacyState.NoFaceLocking
            }
            return currentState
        }

        // ─── PRIORITY 3: Normal (single face, presumed owner) ───
        // Reset everything
        noFaceConsecutiveFrames = 0
        noFaceTimerActive = false
        multiFaceConfirmFrameCount = 0

        // Hold in alert briefly before clearing (anti-flicker)
        if (prevState is PrivacyState.MultipleFacesAlert) {
            multiFaceHoldFrameCount++
            if (multiFaceHoldFrameCount < multiFaceHoldFrames) {
                return PrivacyState.MultipleFacesAlert
            }
        }

        // Cooldown after lock event
        if (prevState is PrivacyState.NoFaceLocking || prevState is PrivacyState.NoFaceWarning) {
            lockCooldownUntil = nowMs + 2000L
        }

        multiFaceHoldFrameCount = 0
        currentState = PrivacyState.Normal
        return currentState
    }

    fun shouldLock(nowMs: Long): Boolean {
        if (currentState !is PrivacyState.NoFaceLocking) return false
        if (nowMs < lockCooldownUntil) return false
        return true
    }

    fun shouldShowOverlay(): Boolean {
        return currentState is PrivacyState.NoFaceWarning ||
               currentState is PrivacyState.NoFaceLocking ||
               currentState is PrivacyState.MultipleFacesAlert
    }

    fun getWarningElapsed(nowMs: Long): Long {
        if (noFaceTimerActive && (currentState is PrivacyState.NoFacePending ||
                currentState is PrivacyState.NoFaceWarning ||
                currentState is PrivacyState.NoFaceLocking)) {
            return nowMs - noFaceTimerStart
        }
        return 0L
    }

    fun reset() {
        currentState = PrivacyState.Normal
        noFaceTimerStart = 0L
        noFaceTimerActive = false
        multiFaceFirstFrameTime = 0L
        multiFaceConfirmFrameCount = 0
        multiFaceHoldFrameCount = 0
        noFaceConsecutiveFrames = 0
        lockCooldownUntil = 0L
    }
}
