package com.example.facelock

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** CameraX analyzer -> ML Kit on-device face detection (contours + eye-open probabilities). */
@OptIn(ExperimentalGetImage::class)
class FaceAnalyzer(private val onFace: (Face?) -> Unit) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )
    @Volatile private var closed = false

    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (closed || media == null) { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                if (!closed) onFace(faces.maxByOrNull { it.boundingBox.width() })
            }
            .addOnFailureListener { if (!closed) onFace(null) }
            .addOnCompleteListener { proxy.close() }
    }

    fun close() { closed = true; detector.close() }
}
