package com.example.facelock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * CameraX analyzer: ML Kit finds the face (eyes, smile, head angle, lip contours for liveness),
 * then MobileFaceNet produces the faceprint used for matching.
 */
@OptIn(ExperimentalGetImage::class)
class FaceAnalyzer(ctx: Context, private val onFace: (Face?, FloatArray?) -> Unit) : ImageAnalysis.Analyzer {

    private val embedder = FaceEmbedder(ctx.applicationContext)
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)          // lips -> "open mouth" check
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eyes + smile
            .build()
    )
    @Volatile private var closed = false

    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (closed || media == null) { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                if (closed) return@addOnSuccessListener
                val face = faces.maxByOrNull { it.boundingBox.width() }
                if (face == null) { onFace(null, null); return@addOnSuccessListener }
                val emb = runCatching { embedder.embed(upright(proxy), face.boundingBox) }.getOrNull()
                onFace(face, emb)
            }
            .addOnFailureListener { if (!closed) onFace(null, null) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun upright(proxy: ImageProxy): Bitmap {
        val bmp = proxy.toBitmap()
        val deg = proxy.imageInfo.rotationDegrees
        if (deg == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    fun close() { closed = true; detector.close(); embedder.close() }
}
