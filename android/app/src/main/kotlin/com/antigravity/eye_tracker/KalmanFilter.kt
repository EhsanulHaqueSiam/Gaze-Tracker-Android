package com.antigravity.eye_tracker

/**
 * 1D Kalman Filter for gaze smoothing.
 * Reduces jitter while maintaining responsiveness.
 */
class KalmanFilter(
    private val processNoise: Double = 0.01,
    private val measurementNoise: Double = 0.1,
    private var estimate: Double = 0.5,
    private var errorCovariance: Double = 1.0
) {
    /**
     * Update the filter with a new measurement.
     * @param measurement The raw noisy measurement
     * @return The smoothed estimate
     */
    fun update(measurement: Double): Double {
        // Prediction step
        val predictedEstimate = estimate
        val predictedErrorCovariance = errorCovariance + processNoise

        // Update step
        val kalmanGain = predictedErrorCovariance / (predictedErrorCovariance + measurementNoise)
        estimate = predictedEstimate + kalmanGain * (measurement - predictedEstimate)
        errorCovariance = (1 - kalmanGain) * predictedErrorCovariance

        return estimate
    }

    /**
     * Reset the filter to initial state.
     */
    fun reset(initialValue: Double = 0.5) {
        estimate = initialValue
        errorCovariance = 1.0
    }

    /**
     * Get current estimate without updating.
     */
    fun getCurrentEstimate(): Double = estimate
}
