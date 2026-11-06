package com.example.facelock

import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Active liveness: the user must BLINK (open -> closed -> open) AND TURN their head
 * (centre -> >=20 deg yaw). A static printed photo never changes eye state or yaw plausibly.
 */
class LivenessDetector {
    private var sawOpen = false
    private var sawClosed = false
    private var blinked = false
    private var sawCenter = false
    private var turned = false

    val passed: Boolean get() = blinked && turned

    val instruction: String
        get() = when {
            !blinked && !turned -> "Blink, then slowly turn your head"
            !blinked -> "Now blink"
            !turned -> "Slowly turn your head to the side"
            else -> "Hold still"
        }

    fun update(face: Face) {
        val l = face.leftEyeOpenProbability
        val r = face.rightEyeOpenProbability
        if (l != null && r != null) {
            val open = l > 0.7f && r > 0.7f
            val closed = l < 0.3f && r < 0.3f
            if (open) { if (sawClosed) blinked = true; sawOpen = true }
            else if (closed && sawOpen) sawClosed = true
        }
        val yaw = abs(face.headEulerAngleY)
        if (yaw < 8f) sawCenter = true
        else if (sawCenter && yaw >= 20f) turned = true
    }
}
