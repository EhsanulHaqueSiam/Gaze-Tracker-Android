package com.antigravity.eye_tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_CHANNEL = "com.antigravity.eye_tracker/camera"
        private const val CALIBRATION_CHANNEL = "com.antigravity.eye_tracker/calibration"
        private const val SETTINGS_CHANNEL = "com.antigravity.eye_tracker/settings"
        private const val GAZE_EVENT_CHANNEL = "com.antigravity.eye_tracker/gaze"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var cameraExecutor: ExecutorService
    private var eventSink: EventChannel.EventSink? = null
    private lateinit var calibrationManager: CalibrationManager
    private var gazeEngine: GazeEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        calibrationManager = CalibrationManager(this)

        // Camera control channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CAMERA_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCamera" -> {
                    if (allPermissionsGranted()) {
                        startCamera()
                        result.success(null)
                    } else {
                        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
                        result.error("PERMISSION_DENIED", "Camera permission needed", null)
                    }
                }
                "stopCamera" -> {
                    stopCamera()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // Calibration control channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CALIBRATION_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCalibration" -> {
                    Log.d(TAG, "startCalibration called")
                    calibrationManager.clearCalibration()
                    if (allPermissionsGranted()) {
                        Log.d(TAG, "Permissions granted, starting camera")
                        startCamera()
                    } else {
                        Log.d(TAG, "Requesting camera permissions")
                        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
                    }
                    result.success(null)
                }
                "addCalibrationPoint" -> {
                    val pointIndex = call.argument<Int>("pointIndex") ?: 0
                    val gazeX = call.argument<Double>("gazeX")?.toFloat() ?: 0.5f
                    val gazeY = call.argument<Double>("gazeY")?.toFloat() ?: 0.5f
                    val targetX = call.argument<Double>("targetX")?.toFloat()
                    val targetY = call.argument<Double>("targetY")?.toFloat()
                    calibrationManager.addCalibrationPoint(pointIndex, gazeX, gazeY, targetX, targetY)
                    result.success(null)
                }
                "finalizeCalibration" -> {
                    calibrationManager.finalizeCalibration()
                    stopCamera()
                    result.success(null)
                }
                "isCalibrated" -> {
                    result.success(calibrationManager.isCalibrated())
                }
                else -> result.notImplemented()
            }
        }

        // Gaze event stream
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, GAZE_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            }
        )

        // Settings channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SETTINGS_CHANNEL).setMethodCallHandler { call, result ->
            val sharedPrefs = getSharedPreferences("EyeTrackerSettings", MODE_PRIVATE)
            when (call.method) {
                "getSettings" -> {
                    val settings = mapOf(
                        "headPoseWeight" to sharedPrefs.getFloat("headPoseWeight", 0.8f).toDouble(),
                        "smoothingFactor" to sharedPrefs.getFloat("smoothingFactor", 0.15f).toDouble(),
                        "snapRadius" to sharedPrefs.getFloat("snapRadius", 120f).toDouble(),
                        "dwellTime" to sharedPrefs.getInt("dwellTime", 1200),
                        "kalmanProcess" to sharedPrefs.getFloat("kalmanProcess", 0.002f).toDouble(),
                        "kalmanMeasurement" to sharedPrefs.getFloat("kalmanMeasurement", 0.05f).toDouble()
                    )
                    result.success(settings)
                }
                "saveSettings" -> {
                    sharedPrefs.edit().apply {
                        putFloat("headPoseWeight", (call.argument<Double>("headPoseWeight") ?: 0.8).toFloat())
                        putFloat("smoothingFactor", (call.argument<Double>("smoothingFactor") ?: 0.15).toFloat())
                        putFloat("snapRadius", (call.argument<Double>("snapRadius") ?: 120.0).toFloat())
                        putInt("dwellTime", call.argument<Int>("dwellTime") ?: 1200)
                        putFloat("kalmanProcess", (call.argument<Double>("kalmanProcess") ?: 0.002).toFloat())
                        putFloat("kalmanMeasurement", (call.argument<Double>("kalmanMeasurement") ?: 0.05).toFloat())
                        apply()
                    }
                    result.success(null)
                }
                "openAccessibilitySettings" -> {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun startCamera() {
        val displayMetrics = resources.displayMetrics
        val ctx = this@MainActivity
        
        // During calibration, we need RAW gaze values (0-1), not screen-transformed values
        // So we create a temporary CalibrationManager that doesn't transform
        val rawCalibrationManager = object : CalibrationManager(ctx) {
            override fun gazeToScreen(gazeX: Float, gazeY: Float, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
                // Return normalized values (0-1) directly, no transformation
                return gazeX to gazeY
            }
        }
        
        gazeEngine = GazeEngine(this, rawCalibrationManager) { gazeX, gazeY ->
            Log.d(TAG, "Gaze callback: $gazeX, $gazeY, eventSink=${eventSink != null}")
            runOnUiThread {
                // For calibration UI, send raw normalized values (0-1)
                eventSink?.success(mapOf("x" to gazeX.toDouble(), "y" to gazeY.toDouble()))
            }
        }.apply {
            screenWidth = 1  // Output will be 0-1 range
            screenHeight = 1
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, gazeEngine!!) }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
                
                // Set flag so AccessibilityService knows camera is in use
                getSharedPreferences("EyeTrackerCamera", Context.MODE_PRIVATE)
                    .edit().putBoolean("mainActivityHasCamera", true).apply()
                
                Log.d(TAG, "Camera started for calibration")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            gazeEngine?.close()
            gazeEngine = null
            
            // Clear flag so AccessibilityService can use camera
            getSharedPreferences("EyeTrackerCamera", Context.MODE_PRIVATE)
                .edit().putBoolean("mainActivityHasCamera", false).apply()
            
            Log.d(TAG, "Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        cameraExecutor.shutdown()
    }
}
