package com.privacyguard.engine

/**
 * Score-based confidence engine that replaces the binary state machine.
 *
 * Scores 0-100 where higher = more threat.
 * Multiple factors contribute: stranger faces, gaze direction, app sensitivity, trust environment.
 */
class ConfidenceEngine(
    /** Base score for detecting a face that doesn't match the owner embedding. */
    private var strangerScore: Int = 40,

    /** Additional score per extra face beyond the first. */
    private var multiFaceScore: Int = 50,

    /** Score added per second of owner absence. */
    private var absencePerSecond: Int = 4,

    /** Extra score when detected face is looking directly at the screen. */
    private var directGazeScore: Int = 20,

    /** Penalty when a sensitive app (e.g., banking) is in foreground. */
    private var sensitiveAppScore: Int = 30,

    /** Maximum number of absence seconds to count. */
    private val maxAbsenceSeconds: Int = 30
) {
    /** Returns the action threshold this confidence maps to. */
    enum class Action(val minScore: Int) {
        NONE(0),
        NOTIFY(20),
        BLUR(40),
        BIOMETRIC_SHIELD(60),
        BIOMETRIC_REQUIRED(80),
        LOCK(100)
    }

    companion object {
        /** Trusted environment reduces score by this amount. */
        const val TRUST_DEDUCTION = 50
        /** Friendly face (owner) completely negates threat. */
        const val OWNER_DEDUCTION = 999
    }

    /** Calculate confidence score from all inputs. */
    fun calculate(
        strangerCount: Int,          // Number of non-owner faces detected
        totalFaceCount: Int,         // Total faces in frame
        ownerPresent: Boolean,       // Is the owner's face detected?
        absenceSeconds: Int,         // Seconds since owner last seen
        isDirectGaze: Boolean,       // Is a stranger looking at screen?
        isSensitiveApp: Boolean,     // Is a high-security app in foreground?
        isTrustedEnvironment: Boolean // Are we on trusted WiFi/BT?
    ): Int {
        // Owner presence overrides everything — 0 threat
        if (ownerPresent && strangerCount == 0) return 0
        if (ownerPresent && strangerCount > 0) {
            // Owner is here but so is a stranger — medium threat
            var score = strangerScore * minOf(strangerCount, 3)
            if (isDirectGaze) score += directGazeScore
            if (isSensitiveApp) score += sensitiveAppScore
            if (isTrustedEnvironment) score = (score - TRUST_DEDUCTION).coerceAtLeast(0)
            return score.coerceIn(0, 100)
        }

        // Owner absent — score builds
        var score = 0

        // Stranger faces
        score += strangerScore * minOf(strangerCount, 3)

        // Multiple faces bonus
        if (totalFaceCount >= 2) score += multiFaceScore

        // Absence duration (per second)
        score += absencePerSecond * minOf(absenceSeconds, maxAbsenceSeconds)

        // Direct gaze
        if (isDirectGaze) score += directGazeScore

        // Sensitive app
        if (isSensitiveApp) score += sensitiveAppScore

        // Trust deduction
        if (isTrustedEnvironment) score = (score - TRUST_DEDUCTION).coerceAtLeast(0)

        return score.coerceIn(0, 100)
    }

    /** Map a score to the appropriate action. */
    fun getAction(score: Int): Action {
        return Action.entries.lastOrNull { score >= it.minScore } ?: Action.NONE
    }
}
