package app.heartpact.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import app.heartpact.exercise.Landmark
import app.heartpact.exercise.Pose
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable

/**
 * Runs MediaPipe's pose landmarker on camera frames, on the device: frames never leave the phone.
 * Each frame is turned upright (and mirrored for the front camera, so the overlay matches what the
 * person sees) before detection; [onPose] gets null when nobody is in frame.
 */
class PoseTracker(context: Context, private val onPose: (Pose?) -> Unit, private val onError: (String) -> Unit) : Closeable {
    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, image ->
                val aspect = image.width.toFloat() / image.height
                val pose = result.landmarks().firstOrNull()?.takeIf { it.size >= Pose.COUNT }?.let { points ->
                    Pose(points.map { Landmark(it.x(), it.y(), it.visibility().orElse(0f)) }, result.timestampMs(), aspect)
                }
                onPose(pose)
            }
            .setErrorListener { error -> Log.w(TAG, "Pose detection failed", error); onError("The camera couldn't read your pose. Try more light.") }
            .build(),
    )

    fun analyze(frame: ImageProxy, mirror: Boolean) {
        frame.use {
            val upright = it.toBitmap().let { bitmap ->
                val matrix = Matrix().apply {
                    postRotate(it.imageInfo.rotationDegrees.toFloat())
                    if (mirror) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            landmarker.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
        }
    }

    override fun close() = landmarker.close()

    private companion object {
        const val MODEL = "pose_landmarker_lite.task"
        const val TAG = "PoseTracker"
    }
}
