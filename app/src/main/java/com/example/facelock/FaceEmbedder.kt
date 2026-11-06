package com.example.facelock

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real face recognition: MobileFaceNet turns a 112x112 face crop into a 192-number "faceprint".
 * Same person -> very similar vectors, different people -> clearly different vectors.
 * Runs 100% on-device. The model expects a batch of 2 images, so the same crop is fed twice.
 */
class FaceEmbedder(ctx: Context) {
    private val interpreter: Interpreter

    init {
        val fd = ctx.assets.openFd(MODEL)
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    /** [upright] must be rotated upright; [box] is ML Kit's bounding box in the same frame. */
    fun embed(upright: Bitmap, box: android.graphics.Rect): FloatArray? {
        val bw = upright.width; val bh = upright.height
        val side = min(min(bw, bh), (max(box.width(), box.height()) * 1.2f).toInt())
        if (side < 40) return null
        val left = (box.centerX() - side / 2).coerceIn(0, bw - side)
        val top = (box.centerY() - side / 2).coerceIn(0, bh - side)
        val crop = Bitmap.createBitmap(upright, left, top, side, side)
        val face = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)

        val pixels = IntArray(SIZE * SIZE)
        face.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val input = ByteBuffer.allocateDirect(2 * SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        repeat(2) {
            for (p in pixels) {
                input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
                input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        input.rewind()
        val out = Array(2) { FloatArray(DIM) }
        interpreter.run(input, out)
        return l2(out[0])
    }

    fun close() = interpreter.close()

    companion object {
        const val MODEL = "MobileFaceNet.tflite"
        const val SIZE = 112
        const val DIM = 192

        private fun l2(v: FloatArray): FloatArray {
            var n = 0f; for (x in v) n += x * x
            val d = sqrt(n).coerceAtLeast(1e-6f)
            return FloatArray(v.size) { v[it] / d }
        }

        /** Both vectors are L2-normalised, so the dot product is the cosine similarity (-1..1). */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return -1f
            var s = 0f; for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
