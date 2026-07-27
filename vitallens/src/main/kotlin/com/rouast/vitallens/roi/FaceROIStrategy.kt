package com.rouast.vitallens.roi

import android.graphics.Bitmap
import com.rouast.vitallens.FaceDetecting
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.ROICalculator
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.vision.FaceDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * Determines the ROI via periodic background face detection, throttled to
 * [detectionIntervalMs]. [determineROI] never blocks on detection itself — it kicks off a
 * detection pass in the background at most once per interval and always immediately returns the
 * most recently known ROI (which may be stale, or `null` before the first detection completes).
 *
 * Mirrors Swift's `FaceROIStrategy` actor: the actor's serialized state access becomes an
 * explicit [Mutex], and its fire-and-forget background `Task` becomes [scope].launch.
 */
class FaceROIStrategy(
    private val detector: FaceDetecting = FaceDetector(),
    private val detectionIntervalMs: Long = 500,
) : ROIStrategy, Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var lastDetectionTime = 0L
    private var currentROI: Rect? = null
    private var isDetecting = false

    override suspend fun determineROI(
        bitmap: Bitmap,
        orientation: ImageOrientation,
        isMirrored: Boolean,
        roiMethod: String,
    ): Rect? {
        val now = System.currentTimeMillis()
        val shouldTrigger = mutex.withLock {
            if (!isDetecting && now - lastDetectionTime >= detectionIntervalMs) {
                isDetecting = true
                true
            } else {
                false
            }
        }
        if (shouldTrigger) {
            scope.launch { performDetection(bitmap, orientation, isMirrored, roiMethod, now) }
        }
        return mutex.withLock { currentROI }
    }

    private suspend fun performDetection(
        bitmap: Bitmap,
        orientation: ImageOrientation,
        isMirrored: Boolean,
        roiMethod: String,
        triggeredAt: Long,
    ) {
        val faceRect = runCatching { detector.detectFace(bitmap, orientation, isMirrored) }.getOrNull()
        val roi = faceRect?.let { ROICalculator.calculateROI(it, roiMethod) }
        mutex.withLock {
            currentROI = roi
            lastDetectionTime = triggeredAt
            isDetecting = false
        }
    }

    /** Cancels any in-flight background detection and releases the detector if it owns one. */
    override fun close() {
        scope.cancel()
        (detector as? Closeable)?.close()
    }
}
