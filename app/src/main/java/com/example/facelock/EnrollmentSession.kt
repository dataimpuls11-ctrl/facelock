package com.example.facelock

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/** Collects 12 geometry samples: 6 frontal + 3 slightly turned each way, for pose robustness. */
class EnrollmentSession {
    val samples = mutableListOf<FloatArray>()
    var prompt by mutableStateOf("Look straight at the camera")
    var progress by mutableFloatStateOf(0f)
    var done by mutableStateOf(false)

    private var center = 0; private var neg = 0; private var pos = 0
    private var last = 0L

    fun onFace(face: Face?) {
        if (done) return
        if (face == null) { prompt = "No face detected"; return }
        val now = SystemClock.elapsedRealtime()
        if (now - last < 250) return

        if (abs(face.headEulerAngleZ) > 20f) { prompt = "Keep your head upright"; return }
        val l = face.leftEyeOpenProbability ?: 0f
        val r = face.rightEyeOpenProbability ?: 0f
        if (l < 0.6f || r < 0.6f) { prompt = "Keep your eyes open"; return }
        val v = FaceGeometry.extract(face) ?: run { prompt = "Move closer / improve lighting"; return }

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
            else -> "Done"
        }
        progress = samples.size / TOTAL.toFloat()
        if (samples.size >= TOTAL) done = true
    }

    companion object {
        const val NEED_CENTER = 6
        const val NEED_SIDE = 3
        const val TOTAL = NEED_CENTER + 2 * NEED_SIDE
    }
}
