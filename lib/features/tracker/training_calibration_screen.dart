import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

/// Comprehensive training calibration screen with HCI enhancements.
/// Features: Audio feedback, haptic feedback, quality score, accuracy visualizer,
/// pulsing animations, dwell progress indicator.
class TrainingCalibrationScreen extends StatefulWidget {
  final VoidCallback onCompleted;

  const TrainingCalibrationScreen({super.key, required this.onCompleted});

  @override
  State<TrainingCalibrationScreen> createState() =>
      _TrainingCalibrationScreenState();
}

class _TrainingCalibrationScreenState extends State<TrainingCalibrationScreen>
    with TickerProviderStateMixin {
  static const _calibrationChannel = MethodChannel(
    'com.antigravity.eye_tracker/calibration',
  );
  static const _eventChannel = EventChannel('com.antigravity.eye_tracker/gaze');

  // 9-point calibration grid
  static const _calibrationPoints = [
    Offset(0.5, 0.5), // Center first
    Offset(0.15, 0.15), Offset(0.85, 0.15),
    Offset(0.15, 0.85), Offset(0.85, 0.85),
    Offset(0.5, 0.15), Offset(0.5, 0.85),
    Offset(0.15, 0.5), Offset(0.85, 0.5),
  ];

  // State
  int _currentPoint = 0;
  CalibrationPhase _phase = CalibrationPhase.instruction;
  final List<Offset> _currentSamples = [];
  StreamSubscription? _gazeSubscription;
  int _countdown = 3;
  Timer? _countdownTimer;
  int _retryCount = 0;

  // HCI: Calibration quality tracking
  final List<double> _pointQualityScores = [];
  double _overallQualityScore = 0.0;

  // HCI: Real-time gaze stability
  double _gazeStability = 0.0;
  Offset? _lastGaze;

  // HCI: Pulsing animation
  late AnimationController _pulseController;
  late Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();

    // Screen wake lock and immersive mode
    WakelockPlus.enable();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);

    // Pulsing animation for calibration dots
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );
    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.3).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
    _pulseController.repeat(reverse: true);

    _startCalibration();
  }

  void _startCalibration() async {
    try {
      await _calibrationChannel.invokeMethod('startCalibration');
    } catch (e) {
      debugPrint('Error starting calibration: $e');
    }

    _gazeSubscription = _eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        final x = (event['x'] as num).toDouble().clamp(0.0, 1.0);
        final y = (event['y'] as num).toDouble().clamp(0.0, 1.0);
        final gaze = Offset(x, y);

        // Calculate stability
        if (_lastGaze != null) {
          final dist = (gaze - _lastGaze!).distance;
          _gazeStability = 1.0 - (dist * 10).clamp(0.0, 1.0);
        }
        _lastGaze = gaze;

        // Collect samples during countdown and collecting phases
        if (_phase == CalibrationPhase.countdown ||
            _phase == CalibrationPhase.collecting) {
          setState(() {
            _currentSamples.add(gaze);
          });

          // Haptic feedback every 10 samples
          if (_currentSamples.length % 10 == 0) {
            HapticFeedback.lightImpact();
          }
        }
      }
    }, onError: (e) => debugPrint('Gaze stream error: $e'));

    // Camera warmup
    await Future.delayed(const Duration(seconds: 3));

    // Play start sound
    _playSound(SystemSoundType.click);
    HapticFeedback.mediumImpact();

    if (mounted) {
      _startNextPoint();
    }
  }

  void _startNextPoint() {
    if (_currentPoint >= _calibrationPoints.length) {
      _finalizeCalibration();
      return;
    }

    setState(() {
      _phase = CalibrationPhase.countdown;
      _countdown = 3;
      _currentSamples.clear();
    });

    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }

      // Audio countdown beep
      _playSound(SystemSoundType.click);
      HapticFeedback.selectionClick();

      setState(() => _countdown--);

      if (_countdown <= 0) {
        timer.cancel();
        _collectSamples();
      }
    });
  }

  void _collectSamples() {
    setState(() => _phase = CalibrationPhase.collecting);

    // Start pulsing when collecting
    _pulseController.repeat(reverse: true);

    Future.delayed(const Duration(seconds: 4), () {
      if (!mounted) return;
      _saveSamples();
    });
  }

  void _saveSamples() async {
    if (_currentSamples.length < 5) {
      _retryCount++;

      // Error haptic
      HapticFeedback.heavyImpact();

      if (_retryCount < 3) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Only ${_currentSamples.length} samples. Retrying... (${_retryCount}/3)',
            ),
            backgroundColor: Colors.orange,
            duration: const Duration(seconds: 1),
          ),
        );
        await Future.delayed(const Duration(milliseconds: 500));
        if (mounted) _startNextPoint();
        return;
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Skipping point'),
            backgroundColor: Colors.red,
          ),
        );
        _retryCount = 0;
        _pointQualityScores.add(0.0);
        setState(() => _currentPoint++);
        await Future.delayed(const Duration(milliseconds: 500));
        if (mounted) _startNextPoint();
        return;
      }
    }

    _retryCount = 0;

    // Calculate point quality score (based on stability)
    final quality = _calculatePointQuality();
    _pointQualityScores.add(quality);

    // Success haptic
    HapticFeedback.mediumImpact();
    _playSound(SystemSoundType.click);

    // Calculate average gaze position
    final avgX =
        _currentSamples.map((o) => o.dx).reduce((a, b) => a + b) /
        _currentSamples.length;
    final avgY =
        _currentSamples.map((o) => o.dy).reduce((a, b) => a + b) /
        _currentSamples.length;

    final targetPoint = _calibrationPoints[_currentPoint];

    try {
      await _calibrationChannel.invokeMethod('addCalibrationPoint', {
        'pointIndex': _currentPoint,
        'gazeX': avgX,
        'gazeY': avgY,
        'targetX': targetPoint.dx,
        'targetY': targetPoint.dy,
      });
    } catch (e) {
      debugPrint('Error adding calibration point: $e');
    }

    setState(() {
      _phase = CalibrationPhase.saved;
      _currentPoint++;
    });

    await Future.delayed(const Duration(milliseconds: 500));
    if (mounted) {
      _startNextPoint();
    }
  }

  double _calculatePointQuality() {
    if (_currentSamples.length < 2) return 0.0;

    // Calculate variance (lower = more stable = higher quality)
    final avgX =
        _currentSamples.map((o) => o.dx).reduce((a, b) => a + b) /
        _currentSamples.length;
    final avgY =
        _currentSamples.map((o) => o.dy).reduce((a, b) => a + b) /
        _currentSamples.length;

    double variance = 0.0;
    for (final s in _currentSamples) {
      variance += pow(s.dx - avgX, 2) + pow(s.dy - avgY, 2);
    }
    variance /= _currentSamples.length;

    // Convert to 0-100 score (lower variance = higher score)
    return (1.0 - (variance * 100).clamp(0.0, 1.0)) * 100;
  }

  void _finalizeCalibration() async {
    // Calculate overall quality
    if (_pointQualityScores.isNotEmpty) {
      _overallQualityScore =
          _pointQualityScores.reduce((a, b) => a + b) /
          _pointQualityScores.length;
    }

    setState(() => _phase = CalibrationPhase.complete);

    // Success haptic and sound
    HapticFeedback.heavyImpact();
    _playSound(SystemSoundType.click);

    try {
      await _calibrationChannel.invokeMethod('finalizeCalibration');
    } catch (e) {
      debugPrint('Error finalizing calibration: $e');
    }

    await Future.delayed(const Duration(seconds: 3));
    if (mounted) {
      widget.onCompleted();
    }
  }

  void _playSound(SystemSoundType type) {
    SystemSound.play(type);
  }

  @override
  void dispose() {
    _gazeSubscription?.cancel();
    _countdownTimer?.cancel();
    _pulseController.dispose();
    WakelockPlus.disable();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.of(context).size;

    if (_phase == CalibrationPhase.instruction) {
      return _buildInstructionScreen();
    }

    if (_phase == CalibrationPhase.complete) {
      return _buildCompleteScreen();
    }

    if (_currentPoint >= _calibrationPoints.length) {
      return _buildCompleteScreen();
    }

    final targetPoint = _calibrationPoints[_currentPoint];

    return Scaffold(
      backgroundColor: const Color(0xFFFFF8E1), // Warm cream for face lighting
      body: Stack(
        children: [
          // Progress bar and info
          Positioned(
            top: 50,
            left: 20,
            right: 20,
            child: Column(
              children: [
                Text(
                  'Point ${_currentPoint + 1} of ${_calibrationPoints.length}',
                  style: const TextStyle(
                    color: Colors.black87,
                    fontSize: 20,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(10),
                  child: LinearProgressIndicator(
                    value: _currentPoint / _calibrationPoints.length,
                    backgroundColor: Colors.grey[300],
                    valueColor: const AlwaysStoppedAnimation(Colors.green),
                    minHeight: 10,
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    // Sample count
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.black12,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        '${_currentSamples.length} samples',
                        style: const TextStyle(
                          color: Colors.black54,
                          fontSize: 14,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    // HCI: Stability indicator
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: _getStabilityColor(),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        'Stability: ${(_gazeStability * 100).toInt()}%',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),

          // HCI: Pulsing animated target dot
          Positioned(
            left: targetPoint.dx * size.width - 50,
            top: targetPoint.dy * size.height - 50,
            child: AnimatedBuilder(
              animation: _pulseAnimation,
              builder: (context, child) {
                return Transform.scale(
                  scale: _phase == CalibrationPhase.collecting
                      ? _pulseAnimation.value
                      : 1.0,
                  child: Container(
                    width: 100,
                    height: 100,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _phase == CalibrationPhase.collecting
                          ? Colors.red
                          : Colors.grey,
                      border: Border.all(color: Colors.white, width: 4),
                      boxShadow: _phase == CalibrationPhase.collecting
                          ? [
                              BoxShadow(
                                color: Colors.red.withAlpha(150),
                                blurRadius: 30,
                                spreadRadius: 10,
                              ),
                            ]
                          : null,
                    ),
                    child: Center(
                      child: _phase == CalibrationPhase.countdown
                          ? Text(
                              '$_countdown',
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 40,
                                fontWeight: FontWeight.bold,
                              ),
                            )
                          : const Icon(
                              Icons.visibility,
                              color: Colors.white,
                              size: 40,
                            ),
                    ),
                  ),
                );
              },
            ),
          ),

          // Instructions
          Positioned(
            bottom: 60,
            left: 20,
            right: 20,
            child: Column(
              children: [
                Text(
                  _phase == CalibrationPhase.collecting
                      ? '👁️ Keep looking at the RED dot!'
                      : '⏳ Get ready to look at the dot...',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: Colors.black87,
                    fontSize: 18,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  _phase == CalibrationPhase.collecting
                      ? 'Stay still until it turns grey'
                      : 'Move eyes to dot when it turns RED',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.black54, fontSize: 14),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Color _getStabilityColor() {
    if (_gazeStability > 0.7) return Colors.green;
    if (_gazeStability > 0.4) return Colors.orange;
    return Colors.red;
  }

  Widget _buildInstructionScreen() {
    return Scaffold(
      backgroundColor: const Color(0xFFFFF8E1),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  color: Colors.deepPurple,
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.visibility,
                  size: 60,
                  color: Colors.white,
                ),
              ),
              const SizedBox(height: 24),
              const Text(
                'Training Calibration',
                style: TextStyle(
                  color: Colors.black87,
                  fontSize: 28,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 16),
              const Text(
                '9 points • 4 seconds each\n\n'
                '1️⃣ Position your face clearly visible\n'
                '2️⃣ When dot turns RED, look at it\n'
                '3️⃣ Hold your gaze until it turns grey\n'
                '4️⃣ Higher stability = better accuracy',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.black54, fontSize: 16),
              ),
              const SizedBox(height: 32),
              const CircularProgressIndicator(color: Colors.deepPurple),
              const SizedBox(height: 16),
              const Text(
                'Camera warming up...',
                style: TextStyle(color: Colors.black45),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCompleteScreen() {
    final qualityColor = _overallQualityScore > 70
        ? Colors.green
        : _overallQualityScore > 40
        ? Colors.orange
        : Colors.red;
    final qualityLabel = _overallQualityScore > 70
        ? 'Excellent'
        : _overallQualityScore > 40
        ? 'Good'
        : 'Try Again';

    return Scaffold(
      backgroundColor: const Color(0xFFFFF8E1),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                _overallQualityScore > 40 ? Icons.check_circle : Icons.warning,
                color: qualityColor,
                size: 100,
              ),
              const SizedBox(height: 24),
              const Text(
                'Calibration Complete!',
                style: TextStyle(
                  color: Colors.black87,
                  fontSize: 28,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 16),
              // HCI: Calibration Quality Score
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 24,
                  vertical: 12,
                ),
                decoration: BoxDecoration(
                  color: qualityColor.withAlpha(30),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: qualityColor, width: 2),
                ),
                child: Column(
                  children: [
                    Text(
                      'Quality Score',
                      style: TextStyle(color: qualityColor, fontSize: 14),
                    ),
                    Text(
                      '${_overallQualityScore.toInt()}%',
                      style: TextStyle(
                        color: qualityColor,
                        fontSize: 48,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Text(
                      qualityLabel,
                      style: TextStyle(
                        color: qualityColor,
                        fontSize: 18,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
              const Text(
                'Enable Accessibility Service for global use!',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.black54, fontSize: 16),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

enum CalibrationPhase { instruction, countdown, collecting, saved, complete }
