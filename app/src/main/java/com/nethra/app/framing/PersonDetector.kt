package com.nethra.app.framing

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * CameraX analyzer that runs ML Kit pose + face detection on-device and reports
 * the person's outline in normalised, upright preview coordinates.
 *
 * Runs on the analysis executor (never the main thread). With
 * STRATEGY_KEEP_ONLY_LATEST, frames that arrive while a frame is being analysed
 * are dropped, so the preview never waits for detection.
 */
class PersonDetector(private val onResult: (Detection) -> Unit) : ImageAnalysis.Analyzer {

    private val pose = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
    )
    private val face = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .build()
    )
    @Volatile private var closed = false
    /** While true, frames are dropped unanalysed (framing locked during a take). */
    @Volatile var paused = false
    private var failures = 0

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (closed || paused || media == null) { proxy.close(); return }
        try {
            val rotation = proxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(media, rotation)
            val upW = if (rotation % 180 == 0) proxy.width else proxy.height
            val upH = if (rotation % 180 == 0) proxy.height else proxy.width

            val poseResult = Tasks.await(pose.process(image), 2, TimeUnit.SECONDS)
            val faces = Tasks.await(face.process(image), 2, TimeUnit.SECONDS)

            var box: NormRect? = null
            var poseConfidence = 0f
            val confident = poseResult.allPoseLandmarks.filter { it.inFrameLikelihood > 0.5f }
            if (confident.size >= 4) {
                val xs = confident.map { it.position.x / upW }
                val ys = confident.map { it.position.y / upH }
                var r = NormRect(xs.min(), ys.min(), xs.max(), ys.max())
                // Landmarks are joints: pad for the head above the eyes, clothing and feet.
                val nose = poseResult.getPoseLandmark(PoseLandmark.NOSE)
                val lShoulder = poseResult.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                if (nose != null && lShoulder != null && nose.inFrameLikelihood > 0.5f) {
                    val headPad = (lShoulder.position.y - nose.position.y) / upH * 0.9f
                    r = r.copy(top = r.top - headPad.coerceAtLeast(0f))
                }
                val padX = r.width * 0.06f
                r = NormRect(r.left - padX, r.top, r.right + padX, r.bottom + r.height * 0.03f)
                box = r
                val key = listOf(
                    PoseLandmark.NOSE, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
                    PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP
                ).mapNotNull { poseResult.getPoseLandmark(it)?.inFrameLikelihood }
                poseConfidence = if (key.isEmpty()) 0f else key.average().toFloat()
            } else if (poseResult.allPoseLandmarks.isNotEmpty()) {
                // Something person-like, but too few joints are clearly visible: report it as
                // not confident rather than pretending nobody is there.
                val all = poseResult.allPoseLandmarks
                box = NormRect(
                    all.minOf { it.position.x } / upW, all.minOf { it.position.y } / upH,
                    all.maxOf { it.position.x } / upW, all.maxOf { it.position.y } / upH
                )
                poseConfidence = all.map { it.inFrameLikelihood }.average().toFloat().coerceAtMost(0.45f)
            }

            val biggestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            if (biggestFace != null) {
                val b = biggestFace.boundingBox
                val f = NormRect(
                    b.left.toFloat() / upW, (b.top - b.height() * 0.25f) / upH,
                    b.right.toFloat() / upW, b.bottom.toFloat() / upH
                )
                box = box?.union(f) ?: f
            }

            // A face alone is a confident "someone is there"; its size is used for close-ups.
            val confidence = when {
                biggestFace != null && poseConfidence > 0f -> maxOf(poseConfidence, 0.75f)
                biggestFace != null -> 0.7f
                else -> poseConfidence
            }
            failures = 0
            onResult(Detection(box?.clamp(), confidence, biggestFace != null, System.currentTimeMillis()))
        } catch (e: Exception) {
            if (++failures % 30 == 1) Log.w(TAG, "Detection failed: ${e.javaClass.simpleName}")
            onResult(Detection(null, 0f, false, System.currentTimeMillis()))
        } finally {
            proxy.close()
        }
    }

    fun close() {
        closed = true
        runCatching { pose.close() }
        runCatching { face.close() }
    }

    companion object { private const val TAG = "PersonDetector" }
}
