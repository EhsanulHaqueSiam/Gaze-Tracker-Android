package com.antigravity.eye_tracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Advanced CalibrationManager with:
 * - Inverse Distance Weighted (IDW) interpolation
 * - Min/max range normalization
 * - Per-point calibration storage
 * - Multi-point averaging
 */
open class CalibrationManager(context: Context) {
    
    companion object {
        private const val TAG = "CalibrationManager"
        private const val PREFS_NAME = "EyeTrackerCalibration"
        
        // IDW power parameter (higher = more weight to nearest points)
        private const val IDW_POWER = 2.0f
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Calibration data: each point maps a gaze position to a screen position
    private val calibrationPoints = mutableListOf<CalibrationPoint>()
    
    // Gaze range observed during calibration
    private var minGazeX = 0.4f
    private var maxGazeX = 0.6f
    private var minGazeY = 0.4f
    private var maxGazeY = 0.6f
    
    data class CalibrationPoint(
        val screenX: Float,  // Target screen position (normalized 0-1)
        val screenY: Float,
        val gazeX: Float,    // Raw gaze when looking at target
        val gazeY: Float
    )
    
    init {
        loadCalibration()
    }
    
    /**
     * Add a calibration point.
     */
    fun addCalibrationPoint(pointIndex: Int, gazeX: Float, gazeY: Float, targetX: Float?, targetY: Float?) {
        val screenX = targetX ?: 0.5f
        val screenY = targetY ?: 0.5f
        
        val point = CalibrationPoint(screenX, screenY, gazeX, gazeY)
        
        // Replace existing or add new
        if (calibrationPoints.size > pointIndex) {
            calibrationPoints[pointIndex] = point
        } else {
            while (calibrationPoints.size < pointIndex) {
                calibrationPoints.add(CalibrationPoint(0.5f, 0.5f, 0.5f, 0.5f))
            }
            calibrationPoints.add(point)
        }
        
        Log.d(TAG, "Added calibration point $pointIndex: screen($screenX, $screenY) -> gaze($gazeX, $gazeY)")
    }
    
    /**
     * Finalize calibration - calculate ranges and save.
     */
    fun finalizeCalibration() {
        if (calibrationPoints.size < 3) {
            Log.w(TAG, "Not enough calibration points (${calibrationPoints.size}), need at least 3")
            return
        }
        
        // Calculate min/max gaze ranges
        minGazeX = calibrationPoints.minOf { it.gazeX }
        maxGazeX = calibrationPoints.maxOf { it.gazeX }
        minGazeY = calibrationPoints.minOf { it.gazeY }
        maxGazeY = calibrationPoints.maxOf { it.gazeY }
        
        // Add 15% margin for movements beyond calibrated range
        val marginX = (maxGazeX - minGazeX) * 0.15f
        val marginY = (maxGazeY - minGazeY) * 0.15f
        minGazeX -= marginX
        maxGazeX += marginX
        minGazeY -= marginY
        maxGazeY += marginY
        
        Log.d(TAG, "Finalized calibration:")
        Log.d(TAG, "  Points: ${calibrationPoints.size}")
        Log.d(TAG, "  Gaze X range: [$minGazeX - $maxGazeX]")
        Log.d(TAG, "  Gaze Y range: [$minGazeY - $maxGazeY]")
        
        saveCalibration()
    }
    
    /**
     * Convert raw gaze to screen coordinates using IDW interpolation.
     * Uses all calibration points with inverse distance weighting.
     */
    open fun gazeToScreen(gazeX: Float, gazeY: Float, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        if (calibrationPoints.isEmpty()) {
            // No calibration - use simple range mapping
            return simpleRangeMapping(gazeX, gazeY, screenWidth, screenHeight)
        }
        
        // Use IDW interpolation from calibration points
        val screenPos = idwInterpolation(gazeX, gazeY)
        
        val screenX = (screenPos.first * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val screenY = (screenPos.second * screenHeight).coerceIn(0f, screenHeight.toFloat())
        
        return screenX to screenY
    }
    
    /**
     * Inverse Distance Weighted interpolation.
     * Predicts screen position based on weighted average of calibration points.
     */
    private fun idwInterpolation(gazeX: Float, gazeY: Float): Pair<Float, Float> {
        var sumWeightedX = 0f
        var sumWeightedY = 0f
        var sumWeights = 0f
        
        for (point in calibrationPoints) {
            // Distance in gaze space
            val dx = gazeX - point.gazeX
            val dy = gazeY - point.gazeY
            val distance = sqrt(dx * dx + dy * dy)
            
            // If very close to a calibration point, return its screen position
            if (distance < 0.001f) {
                return point.screenX to point.screenY
            }
            
            // Inverse distance weight (closer = higher weight)
            val weight = 1.0f / distance.pow(IDW_POWER)
            
            sumWeightedX += weight * point.screenX
            sumWeightedY += weight * point.screenY
            sumWeights += weight
        }
        
        return if (sumWeights > 0) {
            (sumWeightedX / sumWeights) to (sumWeightedY / sumWeights)
        } else {
            0.5f to 0.5f
        }
    }
    
    /**
     * Simple range-based mapping fallback.
     */
    private fun simpleRangeMapping(gazeX: Float, gazeY: Float, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        val rangeX = maxGazeX - minGazeX
        val rangeY = maxGazeY - minGazeY
        
        val normX = if (rangeX > 0.01f) {
            ((gazeX - minGazeX) / rangeX).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        
        val normY = if (rangeY > 0.01f) {
            ((gazeY - minGazeY) / rangeY).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        
        return (normX * screenWidth) to (normY * screenHeight)
    }
    
    private fun saveCalibration() {
        val editor = prefs.edit()
        
        // Save ranges
        editor.putFloat("minGazeX", minGazeX)
        editor.putFloat("maxGazeX", maxGazeX)
        editor.putFloat("minGazeY", minGazeY)
        editor.putFloat("maxGazeY", maxGazeY)
        
        // Save point count and each point
        editor.putInt("pointCount", calibrationPoints.size)
        for ((index, point) in calibrationPoints.withIndex()) {
            editor.putFloat("point_${index}_screenX", point.screenX)
            editor.putFloat("point_${index}_screenY", point.screenY)
            editor.putFloat("point_${index}_gazeX", point.gazeX)
            editor.putFloat("point_${index}_gazeY", point.gazeY)
        }
        
        editor.apply()
        Log.d(TAG, "Calibration saved: ${calibrationPoints.size} points")
    }
    
    private fun loadCalibration() {
        minGazeX = prefs.getFloat("minGazeX", 0.4f)
        maxGazeX = prefs.getFloat("maxGazeX", 0.6f)
        minGazeY = prefs.getFloat("minGazeY", 0.4f)
        maxGazeY = prefs.getFloat("maxGazeY", 0.6f)
        
        val pointCount = prefs.getInt("pointCount", 0)
        calibrationPoints.clear()
        
        for (i in 0 until pointCount) {
            val screenX = prefs.getFloat("point_${i}_screenX", 0.5f)
            val screenY = prefs.getFloat("point_${i}_screenY", 0.5f)
            val gazeX = prefs.getFloat("point_${i}_gazeX", 0.5f)
            val gazeY = prefs.getFloat("point_${i}_gazeY", 0.5f)
            calibrationPoints.add(CalibrationPoint(screenX, screenY, gazeX, gazeY))
        }
        
        Log.d(TAG, "Loaded calibration: ${calibrationPoints.size} points, XRange[$minGazeX-$maxGazeX], YRange[$minGazeY-$maxGazeY]")
    }
    
    fun isCalibrated(): Boolean = prefs.getInt("pointCount", 0) >= 5
    
    fun clearCalibration() {
        calibrationPoints.clear()
        minGazeX = 0.4f
        maxGazeX = 0.6f
        minGazeY = 0.4f
        maxGazeY = 0.6f
        prefs.edit().clear().apply()
        Log.d(TAG, "Calibration cleared")
    }
}
