import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Advanced in-app gaze testing with button snapping and dwell-to-click.
class GazeTestScreen extends StatefulWidget {
  const GazeTestScreen({super.key});

  @override
  State<GazeTestScreen> createState() => _GazeTestScreenState();
}

class _GazeTestScreenState extends State<GazeTestScreen>
    with TickerProviderStateMixin {
  static const _cameraChannel = MethodChannel(
    'com.antigravity.eye_tracker/camera',
  );
  static const _eventChannel = EventChannel('com.antigravity.eye_tracker/gaze');

  // Gaze state
  Offset _gazePosition = const Offset(0.5, 0.5);
  int? _snappedButtonIndex;
  String _lastClickedButton = 'None';
  int _clickCount = 0;
  bool _cameraRunning = false;
  StreamSubscription? _gazeSubscription;

  // Dwell-to-click
  int? _dwellButtonIndex;
  DateTime? _dwellStartTime;
  static const Duration _dwellDuration = Duration(milliseconds: 1200);
  double _dwellProgress = 0.0;
  Timer? _dwellTimer;

  // Smoothing with exponential moving average
  double _smoothedX = 0.5;
  double _smoothedY = 0.5;
  static const double _smoothingFactor = 0.15; // Lower = smoother but slower

  // Button layout
  final List<GlobalKey> _buttonKeys = List.generate(9, (_) => GlobalKey());
  final List<Rect> _buttonRects = List.filled(9, Rect.zero);

  // Snapping
  static const double _snapRadius = 120.0; // pixels

  @override
  void initState() {
    super.initState();
    _startCamera();
    // Update button rects after first frame
    WidgetsBinding.instance.addPostFrameCallback((_) => _updateButtonRects());
  }

  void _updateButtonRects() {
    for (int i = 0; i < _buttonKeys.length; i++) {
      final context = _buttonKeys[i].currentContext;
      if (context != null) {
        final RenderBox box = context.findRenderObject() as RenderBox;
        final position = box.localToGlobal(Offset.zero);
        _buttonRects[i] = Rect.fromLTWH(
          position.dx,
          position.dy,
          box.size.width,
          box.size.height,
        );
      }
    }
  }

  void _startCamera() async {
    try {
      await _cameraChannel.invokeMethod('startCamera');
      setState(() => _cameraRunning = true);
    } on PlatformException catch (e) {
      debugPrint('Error starting camera: $e');
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Camera error: ${e.message}')));
    }

    _gazeSubscription = _eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        final x = (event['x'] as num).toDouble().clamp(0.0, 1.0);
        final y = (event['y'] as num).toDouble().clamp(0.0, 1.0);
        _processGaze(x, y);
      }
    });
  }

  void _processGaze(double rawX, double rawY) {
    // Apply exponential moving average smoothing
    _smoothedX += (rawX - _smoothedX) * _smoothingFactor;
    _smoothedY += (rawY - _smoothedY) * _smoothingFactor;

    final size = MediaQuery.of(context).size;
    final gazeScreenX = _smoothedX * size.width;
    final gazeScreenY = _smoothedY * size.height;

    // Find nearest button for snapping
    int? nearestButton;
    double minDist = double.infinity;

    for (int i = 0; i < _buttonRects.length; i++) {
      final rect = _buttonRects[i];
      if (rect == Rect.zero) continue;

      final center = rect.center;
      final dist = sqrt(
        pow(gazeScreenX - center.dx, 2) + pow(gazeScreenY - center.dy, 2),
      );

      if (dist < _snapRadius && dist < minDist) {
        minDist = dist;
        nearestButton = i;
      }
    }

    setState(() {
      _gazePosition = Offset(_smoothedX, _smoothedY);
      _snappedButtonIndex = nearestButton;
    });

    // Handle dwell-to-click
    if (nearestButton != null) {
      if (_dwellButtonIndex != nearestButton) {
        // Started dwelling on new button
        _dwellButtonIndex = nearestButton;
        _dwellStartTime = DateTime.now();
        _startDwellTimer();
      }
    } else {
      // Not on any button, reset dwell
      _cancelDwell();
    }
  }

  void _startDwellTimer() {
    _dwellTimer?.cancel();
    _dwellTimer = Timer.periodic(const Duration(milliseconds: 50), (_) {
      if (_dwellStartTime == null) return;

      final elapsed = DateTime.now().difference(_dwellStartTime!);
      final progress = (elapsed.inMilliseconds / _dwellDuration.inMilliseconds)
          .clamp(0.0, 1.0);

      setState(() => _dwellProgress = progress);

      if (progress >= 1.0) {
        _triggerClick(_dwellButtonIndex!);
        _cancelDwell();
      }
    });
  }

  void _cancelDwell() {
    _dwellTimer?.cancel();
    _dwellTimer = null;
    setState(() {
      _dwellButtonIndex = null;
      _dwellStartTime = null;
      _dwellProgress = 0.0;
    });
  }

  void _triggerClick(int buttonIndex) {
    setState(() {
      _lastClickedButton = 'Button ${buttonIndex + 1}';
      _clickCount++;
    });

    // Haptic feedback
    HapticFeedback.mediumImpact();

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('✅ Button ${buttonIndex + 1} clicked via GAZE!'),
        backgroundColor: Colors.green,
        duration: const Duration(milliseconds: 800),
      ),
    );
  }

  void _stopCamera() async {
    try {
      await _cameraChannel.invokeMethod('stopCamera');
      setState(() => _cameraRunning = false);
    } catch (e) {
      debugPrint('Error stopping camera: $e');
    }
  }

  @override
  void dispose() {
    _gazeSubscription?.cancel();
    _dwellTimer?.cancel();
    _stopCamera();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.of(context).size;
    final gazeScreenX = _gazePosition.dx * size.width;
    final gazeScreenY = _gazePosition.dy * size.height;

    return Scaffold(
      backgroundColor: Colors.grey[900],
      appBar: AppBar(
        title: const Text('Gaze Test'),
        backgroundColor: Colors.deepPurple,
        actions: [
          IconButton(
            icon: Icon(_cameraRunning ? Icons.videocam : Icons.videocam_off),
            onPressed: _cameraRunning ? _stopCamera : _startCamera,
          ),
        ],
      ),
      body: Stack(
        children: [
          // Status bar
          Positioned(
            top: 10,
            left: 20,
            right: 20,
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Camera: ${_cameraRunning ? "ON ✅" : "OFF ❌"}',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 12,
                        ),
                      ),
                      Text(
                        'Last: $_lastClickedButton | Total: $_clickCount',
                        style: const TextStyle(
                          color: Colors.green,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                  Text(
                    'Gaze: ${(_gazePosition.dx * 100).toStringAsFixed(0)}%, ${(_gazePosition.dy * 100).toStringAsFixed(0)}%',
                    style: const TextStyle(color: Colors.cyan, fontSize: 12),
                  ),
                ],
              ),
            ),
          ),

          // Buttons grid
          Positioned(
            top: size.height * 0.18,
            left: 30,
            right: 30,
            child: GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
                mainAxisSpacing: 20,
                crossAxisSpacing: 20,
                childAspectRatio: 1.0,
              ),
              itemCount: 9,
              itemBuilder: (context, index) => _buildButton(index),
            ),
          ),

          // Gaze cursor (RED)
          Positioned(
            left: gazeScreenX - 20,
            top: gazeScreenY - 20,
            child: IgnorePointer(
              child: Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: Colors.red.withOpacity(0.7),
                  border: Border.all(color: Colors.white, width: 2),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.red.withOpacity(0.5),
                      blurRadius: 15,
                      spreadRadius: 3,
                    ),
                  ],
                ),
              ),
            ),
          ),

          // Instructions
          Positioned(
            bottom: 30,
            left: 20,
            right: 20,
            child: Column(
              children: [
                Text(
                  'Look at a button and hold your gaze to click',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey[400], fontSize: 14),
                ),
                const SizedBox(height: 8),
                Text(
                  'Dwell time: ${_dwellDuration.inMilliseconds}ms',
                  style: TextStyle(color: Colors.grey[600], fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildButton(int index) {
    final isSnapped = _snappedButtonIndex == index;
    final isDwelling = _dwellButtonIndex == index;

    return Container(
      key: _buttonKeys[index],
      decoration: BoxDecoration(
        color: isSnapped ? Colors.deepPurple[300] : Colors.deepPurple,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isSnapped ? Colors.white : Colors.deepPurpleAccent,
          width: isSnapped ? 4 : 2,
        ),
        boxShadow: isSnapped
            ? [
                BoxShadow(
                  color: Colors.deepPurple.withOpacity(0.6),
                  blurRadius: 20,
                  spreadRadius: 5,
                ),
              ]
            : null,
      ),
      child: Stack(
        alignment: Alignment.center,
        children: [
          // Dwell progress ring
          if (isDwelling && _dwellProgress > 0)
            SizedBox(
              width: 80,
              height: 80,
              child: CircularProgressIndicator(
                value: _dwellProgress,
                strokeWidth: 6,
                backgroundColor: Colors.white24,
                valueColor: const AlwaysStoppedAnimation<Color>(Colors.green),
              ),
            ),
          // Button number
          Text(
            '${index + 1}',
            style: TextStyle(
              color: Colors.white,
              fontSize: isSnapped ? 36 : 28,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }
}
