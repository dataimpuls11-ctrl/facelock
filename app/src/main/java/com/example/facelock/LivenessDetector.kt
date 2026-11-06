package com.example.facelock

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.asKotlinRandom

enum class Challenge(val prompt: String) {
    BLINK("Close and open your eyes"),
    SMILE("Smile"),
    OPEN_MOUTH("Open your mouth"),
    TURN_HEAD("Turn your head to the side")
}

/**
 * Live-person check. EVERY attempt picks 3 of the 4 actions in a RANDOM order, so a photo or
 * a pre-recorded video cannot know what will be asked. Each action must be seen as a real
 * change (neutral -> action), one after another.
 */
class LivenessDetector(
    val challenges: List<Challenge> = Challenge.values().toList().shuffled(SecureRandom().asKotlinRandom()).take(3)
) {
    private var index = 0
    private var phase = 0

    val passed: Boolean get() = index >= challenges.size
    val instruction: String
        get() = if (passed) "Hold still" else "${index + 1}/${challenges.size}: ${challenges[index].prompt}"

    fun update(face: Face) {
        if (passed) return
        val done = when (challenges[index]) {
            Challenge.BLINK -> blink(face)
            Challenge.SMILE -> smile(face)
            Challenge.OPEN_MOUTH -> mouth(face)
            Challenge.TURN_HEAD -> turn(face)
        }
        if (done) { index++; phase = 0 }
    }

    // eyes open -> both closed -> open again
    private fun blink(f: Face): Boolean {
        val l = f.leftEyeOpenProbability ?: return false
        val r = f.rightEyeOpenProbability ?: return false
        val open = l > 0.7f && r > 0.7f
        val closed = l < 0.3f && r < 0.3f
        when {
            phase == 0 && open -> phase = 1
            phase == 1 && closed -> phase = 2
            phase == 2 && open -> return true
        }
        return false
    }

    // neutral face -> clear smile
    private fun smile(f: Face): Boolean {
        val s = f.smilingProbability ?: return false
        if (phase == 0 && s < 0.25f) phase = 1
        else if (phase == 1 && s > 0.75f) return true
        return false
    }

    // mouth closed -> mouth clearly open (lip gap / lip width)
    private fun mouth(f: Face): Boolean {
        val ratio = mouthRatio(f) ?: return false
        if (phase == 0 && ratio < 0.10f) phase = 1
        else if (phase == 1 && ratio > 0.30f) return true
        return false
    }

    // facing front -> head turned at least 20 degrees
    private fun turn(f: Face): Boolean {
        val yaw = abs(f.headEulerAngleY)
        if (phase == 0 && yaw < 8f) phase = 1
        else if (phase == 1 && yaw >= 20f) return true
        return false
    }

    private fun mouthRatio(f: Face): Float? {
        val up = f.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
        val lo = f.getContour(FaceContour.LOWER_LIP_TOP)?.points
        val top = f.getContour(FaceContour.UPPER_LIP_TOP)?.points
        if (up.isNullOrEmpty() || lo.isNullOrEmpty() || top.isNullOrEmpty()) return null
        val gap = hypot(
            up.map { it.x }.average() - lo.map { it.x }.average(),
            up.map { it.y }.average() - lo.map { it.y }.average()
        )
        val width = top.maxOf { it.x } - top.minOf { it.x }
        if (width < 1f) return null
        return (gap / width).toFloat()
    }
}
