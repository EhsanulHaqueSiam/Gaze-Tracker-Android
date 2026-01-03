package com.antigravity.eye_tracker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors

/**
 * Accessibility Service for global eye tracking control.
 * Shows a red cursor overlay and enables clicking via gaze.
 */
class EyeAccessibilityService : AccessibilityService(), LifecycleOwner {

    companion object {
        private const val TAG = "EyeAccessibilityService"
        private const val DWELL_THRESHOLD_MS = 1500L
        private const val SNAP_TOLERANCE_PX = 200
        private const val MAX_NODES = 100
        private const val CURSOR_SIZE = 60
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var cursorView: View? = null
    private var highlighterView: View? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var calibrationManager: CalibrationManager
    private var gazeEngine: GazeEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Gaze state
    private var currentGazeX = 0f
    private var currentGazeY = 0f
    private var snappedNode: AccessibilityNodeInfo? = null
    private var dwellStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        calibrationManager = CalibrationManager(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected - setting up overlay")
        
        setupOverlay()
        startGazeTracking()
        
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        Log.d(TAG, "Service fully started")
    }

    private fun setupOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        overlayView = FrameLayout(this)

        // Create circular red cursor with glow effect
        val cursorDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E8FF0000")) // 90% opaque red
            setStroke(4, Color.WHITE)
        }
        
        cursorView = View(this).apply {
            background = cursorDrawable
            layoutParams = FrameLayout.LayoutParams(CURSOR_SIZE, CURSOR_SIZE)
            elevation = 10f
        }

        // Button highlighter (green overlay)
        val highlightDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#4000FF00")) // 25% opaque green
            cornerRadius = 16f
            setStroke(4, Color.parseColor("#80FFFFFF"))
        }
        
        highlighterView = View(this).apply {
            background = highlightDrawable
            visibility = View.GONE
        }

        overlayView?.addView(highlighterView)
        overlayView?.addView(cursorView)

        try {
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
        }
    }

    private fun startGazeTracking() {
        val displayMetrics = resources.displayMetrics
        Log.d(TAG, "Starting gaze tracking, screen: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
        
        // Check if MainActivity already has camera
        val prefs = getSharedPreferences("EyeTrackerCamera", Context.MODE_PRIVATE)
        if (prefs.getBoolean("mainActivityHasCamera", false)) {
            Log.d(TAG, "MainActivity has camera, waiting...")
            mainHandler.postDelayed({ startGazeTracking() }, 2000)
            return
        }
        
        gazeEngine = GazeEngine(this, calibrationManager) { screenX, screenY ->
            mainHandler.post {
                onGazeUpdate(screenX, screenY)
            }
        }.apply {
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
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
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera: ${e.message}")
                // Retry after delay
                mainHandler.postDelayed({ startGazeTracking() }, 3000)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onGazeUpdate(screenX: Float, screenY: Float) {
        if (screenX.isNaN() || screenY.isNaN() || screenX.isInfinite() || screenY.isInfinite()) {
            return
        }
        
        currentGazeX = screenX
        currentGazeY = screenY
        
        // Always update cursor position first
        updateCursor(screenX, screenY)
        
        // Find nearest clickable element
        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Error getting root node", e)
            null
        }
        
        if (rootNode == null) {
            hideHighlighter()
            return
        }

        val nearestNode = findNearestClickable(rootNode, screenX, screenY)
        
        if (nearestNode != null) {
            val bounds = Rect()
            nearestNode.getBoundsInScreen(bounds)
            
            // Snap cursor to button center
            updateCursor(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            showHighlighter(bounds)
            handleDwell(nearestNode)
        } else {
            hideHighlighter()
            resetDwell()
        }
    }

    private fun findNearestClickable(root: AccessibilityNodeInfo, x: Float, y: Float): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var minDist = Double.MAX_VALUE

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < MAX_NODES) {
            val node = queue.removeFirst()
            count++

            try {
                if (node.isVisibleToUser && node.isClickable) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    
                    val centerX = bounds.centerX().toDouble()
                    val centerY = bounds.centerY().toDouble()
                    val dist = kotlin.math.hypot(x - centerX, y - centerY)

                    if (dist < SNAP_TOLERANCE_PX && dist < minDist) {
                        minDist = dist
                        bestNode = node
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (e: Exception) {
                // Node may have been recycled
            }
        }

        return bestNode
    }

    private fun handleDwell(node: AccessibilityNodeInfo) {
        if (snappedNode == node) {
            if (System.currentTimeMillis() - dwellStartTime > DWELL_THRESHOLD_MS) {
                performClick(node)
                resetDwell()
            }
        } else {
            snappedNode = node
            dwellStartTime = System.currentTimeMillis()
        }
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        try {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Click performed: $success")
            
            // Visual feedback - flash green
            val originalBg = cursorView?.background
            cursorView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.GREEN)
                setStroke(4, Color.WHITE)
            }
            mainHandler.postDelayed({
                cursorView?.background = originalBg
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Click failed", e)
        }
    }

    private fun resetDwell() {
        snappedNode = null
        dwellStartTime = 0
    }

    private fun updateCursor(x: Float, y: Float) {
        cursorView?.let {
            it.x = x - CURSOR_SIZE / 2f
            it.y = y - CURSOR_SIZE / 2f
        }
    }

    private fun showHighlighter(bounds: Rect) {
        highlighterView?.let {
            it.layoutParams = FrameLayout.LayoutParams(bounds.width(), bounds.height())
            it.x = bounds.left.toFloat()
            it.y = bounds.top.toFloat()
            it.visibility = View.VISIBLE
        }
    }

    private fun hideHighlighter() {
        highlighterView?.visibility = View.GONE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        
        gazeEngine?.close()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        
        try {
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay", e)
        }
        
        Log.d(TAG, "Service destroyed")
    }
}
