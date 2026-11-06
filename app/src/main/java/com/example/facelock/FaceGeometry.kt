package com.example.facelock

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts ML Kit face contours (~130 points) into a pose-normalised geometry vector:
 * translated to the eye midpoint, rotated so the eye line is horizontal, and scaled by the
 * inter-eye distance. This makes the vector invariant to distance, position and in-plane roll.
 */
object FaceGeometry {
    /** Mean per-point distance (in inter-eye units) at or below which two faces "match". TUNE ON REAL DEVICES. */
    const val MATCH_THRESHOLD = 0.08f

    private val CONTOURS = intArrayOf(
        FaceContour.FACE,
        FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM,
        FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM,
        FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
        FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
        FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM,
        FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM
    )

    fun extract(face: Face): FloatArray? {
        val le = face.getContour(FaceContour.LEFT_EYE)?.points ?: return null
        val re = face.getContour(FaceContour.RIGHT_EYE)?.points ?: return null
        if (le.isEmpty() || re.isEmpty()) return null

        val lx = le.map { it.x }.average().toFloat(); val ly = le.map { it.y }.average().toFloat()
        val rx = re.map { it.x }.average().toFloat(); val ry = re.map { it.y }.average().toFloat()
        val scale = hypot(rx - lx, ry - ly)
        if (scale < 1f) return null
        val angle = atan2(ry - ly, rx - lx)
        val c = cos(angle); val s = sin(angle)
        val ox = (lx + rx) / 2f; val oy = (ly + ry) / 2f

        val out = ArrayList<Float>(300)
        for (type in CONTOURS) {
            val pts = face.getContour(type)?.points ?: return null
            for (p in pts) {
                val x = p.x - ox; val y = p.y - oy
                out.add((x * c + y * s) / scale)
                out.add((-x * s + y * c) / scale)
            }
        }
        return out.toFloatArray()
    }

    fun distance(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return Float.MAX_VALUE
        var sum = 0f
        val n = a.size / 2
        for (i in 0 until n) {
            val dx = a[2 * i] - b[2 * i]; val dy = a[2 * i + 1] - b[2 * i + 1]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / n
    }
}
