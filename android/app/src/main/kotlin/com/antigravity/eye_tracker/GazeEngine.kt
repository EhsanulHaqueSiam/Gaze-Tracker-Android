package com.antigravity.eye_tracker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Advanced GazeEngine with:
 * - MediaPipe Iris tracking
 * - Head pose compensation
 * - Fixation detection (stable gaze filtering)
 * - Adaptive smoothing (more smoothing during saccades)
 * - Outlier rejection
 * - Bilinear interpolation from calibration grid
 */
class GazeEngine(
    private val context: Context,
    private val calibrationManager: CalibrationManager,
    private val onGazeUpdate: (Float, Float) -> Unit
) : ImageAnalysis.Analyzer {
    
    companion object {
        private const val TAG = "GazeEngine"
        
        // MediaPipe Iris Landmark Indices
        private const val LEFT_IRIS_CENTER = 468
        private const val RIGHT_IRIS_CENTER = 473
        
        // Left eye corners
        private const val LEFT_EYE_OUTER = 33
        private const val LEFT_EYE_INNER = 133
        private const val LEFT_EYE_TOP = 159
        private const val LEFT_EYE_BOTTOM = 145
        
        // Right eye corners  
        private const val RIGHT_EYE_INNER = 362
        private const val RIGHT_EYE_OUTER = 263
        private const val RIGHT_EYE_TOP = 386
        private const val RIGHT_EYE_BOTTOM = 374
        
        // Head pose landmarks
        private const val NOSE_TIP = 1
        private const val FACE_CENTER = 168
        
        // HEAD vs IRIS BALANCE (reduced for better iris tracking)
        private const val HEAD_POSE_WEIGHT = 0.5f  // 50% head, 50% iris (was 85%)
        
        // Fixation detection thresholds
        private const val FIXATION_THRESHOLD = 0.015f  // Max movement to be considered fixation
        private const val SACCADE_THRESHOLD = 0.05f    // Movement above this is a saccade
        
        // Smoothing (adaptive)
        private const val SMOOTH_FIXATION = 0.08f    // More smoothing during fixation
        private const val SMOOTH_SACCADE = 0.25f     // Less smoothing during saccades
        
        // History for outlier rejection and stability
        private const val HISTORY_SIZE = 10
        private const val OUTLIER_THRESHOLD = 3.0f  // Standard deviations
        
        // Blink detection (Eye Aspect Ratio) - lowered for edge gaze detection
        private const val EAR_BLINK_THRESHOLD = 0.08f  // Below this = eye closed (was 0.12)
        private const val LEFT_EYE_UPPER = 159
        private const val LEFT_EYE_LOWER = 145
        private const val RIGHT_EYE_UPPER = 386
        private const val RIGHT_EYE_LOWER = 374
    }
    
    private var faceLandmarker: FaceLandmarker? = null
    
    // Kalman filters for smooth gaze
    private val kalmanX = KalmanFilter(processNoise = 0.001, measurementNoise = 0.03)
    private val kalmanY = KalmanFilter(processNoise = 0.001, measurementNoise = 0.03)
    
    // History for outlier detection
    private val gazeHistoryX = mutableListOf<Float>()
    private val gazeHistoryY = mutableListOf<Float>()
    
    // Previous values for velocity calculation
    private var prevGazeX = 0.5f
    private var prevGazeY = 0.5f
    private var prevTime = 0L
    
    // Adaptive smoothing state
    private var currentSmoothing = SMOOTH_FIXATION
    
    // Smoothed output
    private var smoothedX = 0.5f
    private var smoothedY = 0.5f
    
    // Screen dimensions
    var screenWidth: Int = 1080
    var screenHeight: Int = 2400
    
    init {
        setupFaceLandmarker()
    }
    
    private fun setupFaceLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(0.6f)  // Higher confidence
                .setMinFacePresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .setNumFaces(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onFaceResult)
                .setErrorListener { error -> Log.e(TAG, "MediaPipe error: ${error.message}") }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "FaceLandmarker initialized with advanced settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FaceLandmarker", e)
        }
    }
    
    override fun analyze(imageProxy: ImageProxy) {
        if (faceLandmarker == null) {
            imageProxy.close()
            return
        }
        
        try {
            val frameTime = SystemClock.uptimeMillis()
            val bitmap = imageProxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            faceLandmarker?.detectAsync(mpImage, frameTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
        } finally {
            imageProxy.close()
        }
    }
    
    private fun onFaceResult(result: FaceLandmarkerResult, inputImage: MPImage) {
        val landmarks = result.faceLandmarks()
        if (landmarks.isEmpty()) return
        
        val face = landmarks[0]
        if (face.size < 478) return
        
        try {
            val currentTime = SystemClock.uptimeMillis()
            
            // Extract iris centers
            val leftIris = face[LEFT_IRIS_CENTER]
            val rightIris = face[RIGHT_IRIS_CENTER]
            
            // Extract eye corners
            val leftOuter = face[LEFT_EYE_OUTER]
            val leftInner = face[LEFT_EYE_INNER]
            val leftTop = face[LEFT_EYE_TOP]
            val leftBottom = face[LEFT_EYE_BOTTOM]
            
            val rightOuter = face[RIGHT_EYE_OUTER]
            val rightInner = face[RIGHT_EYE_INNER]
            val rightTop = face[RIGHT_EYE_TOP]
            val rightBottom = face[RIGHT_EYE_BOTTOM]
            
            // BLINK DETECTION DISABLED - EAR calculation broken with normalized coords
            // TODO: Fix EAR with proper threshold for normalized coordinates (0-1 range)
            // val leftEAR = calculateEAR(leftTop.y(), leftBottom.y(), leftOuter.x(), leftInner.x())
            // val rightEAR = calculateEAR(rightTop.y(), rightBottom.y(), rightOuter.x(), rightInner.x())
            // val avgEAR = (leftEAR + rightEAR) / 2.0f
            // if (avgEAR < EAR_BLINK_THRESHOLD) {
            //     Log.d(TAG, "Blink detected (EAR: $avgEAR)")
            //     return
            // }
            
            // Calculate iris position relative to eye (0-1 within eye bounds)
            val leftGazeX = calculateNormalizedPosition(leftIris.x(), leftOuter.x(), leftInner.x())
            val leftGazeY = calculateNormalizedPosition(leftIris.y(), leftTop.y(), leftBottom.y())
            
            val rightGazeX = calculateNormalizedPosition(rightIris.x(), rightInner.x(), rightOuter.x())
            val rightGazeY = calculateNormalizedPosition(rightIris.y(), rightTop.y(), rightBottom.y())
            
            // Average both eyes
            var irisGazeX = (leftGazeX + rightGazeX) / 2.0
            var irisGazeY = (leftGazeY + rightGazeY) / 2.0
            
            // Mirror correction for front camera (both axes)
            irisGazeX = 1.0 - irisGazeX
            irisGazeY = 1.0 - irisGazeY  // Also invert Y for correct mapping
            
            // Head pose (face center position)
            val faceCenter = face[FACE_CENTER]
            val headPoseX = 1.0 - faceCenter.x().toDouble()
            val headPoseY = faceCenter.y().toDouble()
            
            // Blend iris with head pose (HEAD-FIRST)
            val rawGazeX = (irisGazeX * (1.0 - HEAD_POSE_WEIGHT) + headPoseX * HEAD_POSE_WEIGHT).toFloat()
            val rawGazeY = (irisGazeY * (1.0 - HEAD_POSE_WEIGHT) + headPoseY * HEAD_POSE_WEIGHT).toFloat()
            
            // Skip invalid values
            if (rawGazeX.isNaN() || rawGazeY.isNaN()) return
            
            // Outlier rejection
            if (!isOutlier(rawGazeX, rawGazeY)) {
                // Add to history
                addToHistory(rawGazeX, rawGazeY)
                
                // Calculate velocity for adaptive smoothing
                val dt = if (prevTime > 0) (currentTime - prevTime) / 1000f else 0.033f
                val velocity = if (dt > 0) {
                    sqrt((rawGazeX - prevGazeX).pow(2) + (rawGazeY - prevGazeY).pow(2)) / dt
                } else 0f
                
                // Adaptive smoothing: less smoothing during fast movements
                currentSmoothing = when {
                    velocity > SACCADE_THRESHOLD -> SMOOTH_SACCADE
                    velocity < FIXATION_THRESHOLD -> SMOOTH_FIXATION
                    else -> (SMOOTH_FIXATION + SMOOTH_SACCADE) / 2f
                }
                
                // Apply Kalman filter
                val kalmanX = kalmanX.update(rawGazeX.toDouble()).toFloat()
                val kalmanY = kalmanY.update(rawGazeY.toDouble()).toFloat()
                
                // Apply exponential smoothing on top
                smoothedX += (kalmanX - smoothedX) * currentSmoothing
                smoothedY += (kalmanY - smoothedY) * currentSmoothing
                
                // Apply calibration mapping
                val screenCoords = calibrationManager.gazeToScreen(
                    smoothedX,
                    smoothedY,
                    screenWidth,
                    screenHeight
                )
                
                val finalX = screenCoords.first.coerceIn(0f, screenWidth.toFloat())
                val finalY = screenCoords.second.coerceIn(0f, screenHeight.toFloat())
                
                onGazeUpdate(finalX, finalY)
                
                prevGazeX = rawGazeX
                prevGazeY = rawGazeY
            }
            
            prevTime = currentTime
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing landmarks", e)
        }
    }
    
    private fun calculateNormalizedPosition(pos: Float, min: Float, max: Float): Double {
        val range = max - min
        return if (abs(range) > 0.001f) {
            ((pos - min) / range).toDouble().coerceIn(0.0, 1.0)
        } else 0.5
    }
    
    private fun isOutlier(x: Float, y: Float): Boolean {
        if (gazeHistoryX.size < 3) return false
        
        val meanX = gazeHistoryX.average().toFloat()
        val meanY = gazeHistoryY.average().toFloat()
        val stdX = calculateStdDev(gazeHistoryX, meanX)
        val stdY = calculateStdDev(gazeHistoryY, meanY)
        
        if (stdX < 0.001f || stdY < 0.001f) return false
        
        val zScoreX = abs(x - meanX) / stdX
        val zScoreY = abs(y - meanY) / stdY
        
        return zScoreX > OUTLIER_THRESHOLD || zScoreY > OUTLIER_THRESHOLD
    }
    
    private fun calculateStdDev(values: List<Float>, mean: Float): Float {
        if (values.isEmpty()) return 0f
        val variance = values.map { (it - mean).pow(2) }.average().toFloat()
        return sqrt(variance)
    }
    
    private fun addToHistory(x: Float, y: Float) {
        gazeHistoryX.add(x)
        gazeHistoryY.add(y)
        while (gazeHistoryX.size > HISTORY_SIZE) gazeHistoryX.removeAt(0)
        while (gazeHistoryY.size > HISTORY_SIZE) gazeHistoryY.removeAt(0)
    }
    
    /**
     * Calculate Eye Aspect Ratio (EAR) for blink detection.
     * EAR = (vertical distance) / (horizontal distance)
     */
    private fun calculateEAR(topY: Float, bottomY: Float, outerX: Float, innerX: Float): Float {
        val verticalDist = abs(bottomY - topY)
        val horizontalDist = abs(innerX - outerX)
        return if (horizontalDist > 0.001f) verticalDist / horizontalDist else 0f
    }
    
    fun close() {
        faceLandmarker?.close()
    }
}
