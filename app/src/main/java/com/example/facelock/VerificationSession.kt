package com.example.facelock

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Success needs ALL of this, in order:
 *  1. MATCH     - 5 frontal frames strongly match the enrolled face
 *  2. LIVENESS  - 3 RANDOM live actions (blink / smile / open mouth / turn head); the same
 *                 person must stay in front of the camera the whole time
 *  3. RECHECK   - 3 more strong frontal matches after the actions
 * No PIN / pattern / fingerprint fallback.
 */
class VerificationSession(private val templates: List<FloatArray>) {
    var prompt by mutableStateOf("Look at the camera")
    var success by mutableStateOf(false)

    private enum class Stage { MATCH, LIVENESS, RECHECK }
    private var stage = Stage.MATCH
    private var liveness = LivenessDetector()
    private var streak = 0
    private var mismatches = 0
    private var swaps = 0
    private var fails = 0
    private var attemptStart = 0L
    private var lastFace = 0L
    private var lockedUntil = 0L

    fun onFace(face: Face?, emb: FloatArray?) {
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
                val s = score(emb) ?: return
                if (s >= MATCH_THRESHOLD) {
                    prompt = "Verifying…"
                    if (++streak >= MATCH_FRAMES) {
                        stage = Stage.LIVENESS; liveness = LivenessDetector(); prompt = liveness.instruction
                    }
                } else { streak = 0; if (++mismatches >= MAX_MISMATCH) fail("Face not recognised") }
            }
            Stage.LIVENESS -> {
                // The person doing the actions must still be the enrolled person.
                if (frontal(face)) {
                    val s = score(emb)
                    if (s != null && s < SWAP_THRESHOLD && ++swaps >= 4) { fail("Face changed during check"); return }
                }
                liveness.update(face)
                prompt = liveness.instruction
                if (liveness.passed) { stage = Stage.RECHECK; streak = 0; prompt = "Look at the camera and hold still" }
            }
            Stage.RECHECK -> {
                if (!frontal(face)) { prompt = "Face the camera"; return }
                val s = score(emb) ?: return
                if (s >= MATCH_THRESHOLD) { if (++streak >= RECHECK_FRAMES) success = true }
                else { streak = 0; if (++mismatches >= MAX_MISMATCH) fail("Face not recognised") }
            }
        }
    }

    private fun frontal(f: Face) = abs(f.headEulerAngleY) < 15f && abs(f.headEulerAngleZ) < 20f

    /** Average of the 3 best template similarities (1.0 = identical). null = no faceprint this frame. */
    private fun score(emb: FloatArray?): Float? {
        if (emb == null) return null
        val sims = templates.filter { it.size == emb.size }.map { FaceEmbedder.cosine(emb, it) }.sortedDescending()
        if (sims.isEmpty()) return -1f
        val s = sims.take(3).average().toFloat()
        Log.d("FaceLock", "similarity=$s stage=$stage")
        return s
    }

    private fun reset() {
        stage = Stage.MATCH; streak = 0; mismatches = 0; swaps = 0; attemptStart = 0L
        liveness = LivenessDetector() // brand-new random actions every attempt
    }

    private fun fail(msg: String) {
        reset()
        if (++fails >= 5) { lockedUntil = SystemClock.elapsedRealtime() + 30_000; fails = 0 }
        prompt = msg
    }

    companion object {
        /** Strict match. Higher = stricter (try 0.78 if strangers still get in, 0.62 if you get rejected). */
        const val MATCH_THRESHOLD = 0.70f
        /** During the live actions, anyone scoring below this is treated as a different person. */
        const val SWAP_THRESHOLD = 0.50f
        const val MATCH_FRAMES = 5
        const val RECHECK_FRAMES = 3
        const val ATTEMPT_TIMEOUT_MS = 25_000L
        const val MAX_MISMATCH = 12
    }
}
