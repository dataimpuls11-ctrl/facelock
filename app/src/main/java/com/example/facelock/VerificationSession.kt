package com.example.facelock

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Verification pipeline (success requires ALL stages, in order):
 *   1. MATCH     - 3 consecutive frontal frames match the enrolled profile
 *   2. LIVENESS  - blink + head turn performed live
 *   3. RECHECK   - 2 more frontal matches (stops swapping the person mid-challenge)
 * There is deliberately no PIN / pattern / fingerprint fallback.
 */
class VerificationSession(private val templates: List<FloatArray>) {
    var prompt by mutableStateOf("Look at the camera")
    var success by mutableStateOf(false)

    private enum class Stage { MATCH, LIVENESS, RECHECK }
    private var stage = Stage.MATCH
    private var liveness = LivenessDetector()
    private var streak = 0
    private var mismatches = 0
    private var fails = 0
    private var attemptStart = 0L
    private var lastFace = 0L
    private var lockedUntil = 0L

    fun onFace(face: Face?) {
        if (success) return
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) {
            prompt = "Too many failed attempts. Wait ${(lockedUntil - now) / 1000 + 1}s"
            return
        }
        if (face == null) {
            if (now - lastFace > 1500) { reset(); prompt = "Look at the camera" }
            return
        }
        lastFace = now
        if (attemptStart == 0L) attemptStart = now
        if (now - attemptStart > ATTEMPT_TIMEOUT_MS) { fail("Verification timed out"); return }

        when (stage) {
            Stage.MATCH -> {
                if (!frontal(face)) { prompt = "Face the camera"; return }
                if (matches(face)) {
                    prompt = "Verifying…"
                    if (++streak >= 3) { stage = Stage.LIVENESS; liveness = LivenessDetector(); prompt = liveness.instruction }
                } else { streak = 0; if (++mismatches >= MAX_MISMATCH) fail("Face not recognised") }
            }
            Stage.LIVENESS -> {
                liveness.update(face)
                prompt = liveness.instruction
                if (liveness.passed) { stage = Stage.RECHECK; streak = 0; prompt = "Face the camera" }
            }
            Stage.RECHECK -> {
                if (!frontal(face)) { prompt = "Face the camera"; return }
                if (matches(face)) { if (++streak >= 2) success = true }
                else { streak = 0; if (++mismatches >= MAX_MISMATCH) fail("Face not recognised") }
            }
        }
    }

    private fun frontal(f: Face) = abs(f.headEulerAngleY) < 15f && abs(f.headEulerAngleZ) < 20f

    private fun matches(face: Face): Boolean {
        val v = FaceGeometry.extract(face) ?: return false
        if (templates.isEmpty()) return false
        return templates.minOf { FaceGeometry.distance(v, it) } <= FaceGeometry.MATCH_THRESHOLD
    }

    private fun reset() {
        stage = Stage.MATCH; streak = 0; mismatches = 0; attemptStart = 0L; liveness = LivenessDetector()
    }

    private fun fail(msg: String) {
        reset()
        if (++fails >= 5) { lockedUntil = SystemClock.elapsedRealtime() + 30_000; fails = 0 }
        prompt = msg
    }

    companion object {
        const val ATTEMPT_TIMEOUT_MS = 15_000L
        const val MAX_MISMATCH = 12
    }
}
