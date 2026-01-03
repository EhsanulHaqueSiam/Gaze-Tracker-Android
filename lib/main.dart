import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'features/tracker/gaze_test_screen.dart';
import 'features/tracker/training_calibration_screen.dart';
import 'features/tracker/settings_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const EyeTrackerApp());
}

class EyeTrackerApp extends StatelessWidget {
  const EyeTrackerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Eye Tracker',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.deepPurple,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const _calibrationChannel = MethodChannel(
    'com.antigravity.eye_tracker/calibration',
  );
  bool _isCalibrated = false;

  @override
  void initState() {
    super.initState();
    _checkCalibration();
  }

  Future<void> _checkCalibration() async {
    try {
      final calibrated = await _calibrationChannel.invokeMethod<bool>(
        'isCalibrated',
      );
      setState(() => _isCalibrated = calibrated ?? false);
    } catch (e) {
      debugPrint('Error checking calibration: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Eye Tracker'),
        backgroundColor: Colors.deepPurple,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            tooltip: 'Settings',
            onPressed: () {
              Navigator.of(
                context,
              ).push(MaterialPageRoute(builder: (_) => const SettingsScreen()));
            },
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status card
            Card(
              color: _isCalibrated ? Colors.green[900] : Colors.orange[900],
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Icon(
                      _isCalibrated ? Icons.check_circle : Icons.warning,
                      color: Colors.white,
                      size: 40,
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            _isCalibrated ? 'Calibrated' : 'Not Calibrated',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 18,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          Text(
                            _isCalibrated
                                ? 'Ready to use globally'
                                : 'Run training calibration first',
                            style: TextStyle(
                              color: Colors.white.withOpacity(0.8),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 32),

            // Training Calibration (Primary action)
            FilledButton.icon(
              onPressed: () {
                Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => TrainingCalibrationScreen(
                      onCompleted: () {
                        Navigator.pop(context);
                        _checkCalibration();
                      },
                    ),
                  ),
                );
              },
              icon: const Icon(Icons.school),
              label: const Text('Train Calibration (9-point)'),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 20),
                textStyle: const TextStyle(fontSize: 18),
              ),
            ),

            const SizedBox(height: 16),

            // Test Gaze
            OutlinedButton.icon(
              onPressed: () {
                Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const GazeTestScreen()),
                );
              },
              icon: const Icon(Icons.play_arrow),
              label: const Text('Test Gaze Tracking'),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
            ),

            const SizedBox(height: 32),

            // Instructions
            Card(
              color: Colors.grey[850],
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'How to Use',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _buildStep('1', 'Run Training Calibration'),
                    _buildStep('2', 'Test gaze tracking in-app'),
                    _buildStep('3', 'Enable Accessibility Service in Settings'),
                    _buildStep('4', 'Use eye tracking globally!'),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 16),

            // Open Accessibility Settings
            TextButton.icon(
              onPressed: () {
                const platform = MethodChannel(
                  'com.antigravity.eye_tracker/settings', // Fixed: was 'camera'
                );
                platform.invokeMethod('openAccessibilitySettings');
              },
              icon: const Icon(Icons.settings_accessibility),
              label: const Text('Open Accessibility Settings'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStep(String number, String text) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: Colors.deepPurple,
              shape: BoxShape.circle,
            ),
            child: Center(
              child: Text(
                number,
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Text(text, style: const TextStyle(fontSize: 14)),
        ],
      ),
    );
  }
}
