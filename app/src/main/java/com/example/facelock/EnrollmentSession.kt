package com.example.facelock

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Collects 12 faceprints (6 frontal + 3 slightly turned each way) AND requires one real blink,
 * so a photo can never be enrolled as the owner.
 */
class EnrollmentSession {
    val samples = mutableListOf<FloatArray>()
    var prompt by mutableStateOf("Look straight at the camera")
    var progress by mutableFloatStateOf(0f)
    var done by mutableStateOf(false)

    private var center = 0; private var neg = 0; private var pos = 0
    private var last = 0L
    // blink tracking: open -> closed -> open
    private var sawOpen = false; private var sawClosed = false; private var blinked = false

    fun onFace(face: Face?, embedding: FloatArray?) {
        if (done) return
        if (face == null) { prompt = "No face detected"; return }
        trackBlink(face)

        if (samples.size >= TOTAL) {
            if (blinked) { prompt = "Done"; done = true } else prompt = "Now blink once"
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - last < 250) return
        if (abs(face.headEulerAngleZ) > 20f) { prompt = "Keep your head upright"; return }
        val l = face.leftEyeOpenProbability ?: 0f
        val r = face.rightEyeOpenProbability ?: 0f
        if (l < 0.6f || r < 0.6f) { prompt = "Keep your eyes open"; return }
        val v = embedding ?: run { prompt = "Move closer / improve lighting"; return }

        val yaw = face.headEulerAngleY
        val added = when {
            abs(yaw) < 8f && center < NEED_CENTER -> { center++; true }
            yaw in -30f..-12f && neg < NEED_SIDE -> { neg++; true }
            yaw in 12f..30f && pos < NEED_SIDE -> { pos++; true }
            else -> false
        }
        if (added) { samples.add(v); last = now }

        prompt = when {
            center < NEED_CENTER -> "Look straight at the camera"
            neg < NEED_SIDE || pos < NEED_SIDE -> "Slowly turn your head slightly side to side"
            else -> "Now blink once"
        }
        progress = samples.size / TOTAL.toFloat()
    }

    private fun trackBlink(face: Face) {
        val l = face.leftEyeOpenProbability ?: return
        val r = face.rightEyeOpenProbability ?: return
        val open = l > 0.7f && r > 0.7f
        val closed = l < 0.3f && r < 0.3f
        if (open) { if (sawClosed) blinked = true; sawOpen = true }
        else if (closed && sawOpen) sawClosed = true
    }

    companion object {
        const val NEED_CENTER = 6
        const val NEED_SIDE = 3
        const val TOTAL = NEED_CENTER + 2 * NEED_SIDE
    }
}
