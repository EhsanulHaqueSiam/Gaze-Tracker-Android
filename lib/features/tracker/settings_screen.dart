import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Settings screen for adjusting eye tracking accuracy parameters.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  static const _settingsChannel = MethodChannel(
    'com.antigravity.eye_tracker/settings',
  );

  // Adjustable parameters
  double _headPoseWeight = 0.8; // 0 = iris only, 1 = head only
  double _smoothingFactor = 0.15; // Lower = smoother
  double _snapRadius = 120; // pixels
  int _dwellTime = 1200; // milliseconds
  double _kalmanProcess = 0.002;
  double _kalmanMeasurement = 0.05;

  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    try {
      final settings = await _settingsChannel.invokeMethod<Map>('getSettings');
      if (settings != null) {
        setState(() {
          _headPoseWeight =
              (settings['headPoseWeight'] as num?)?.toDouble() ?? 0.8;
          _smoothingFactor =
              (settings['smoothingFactor'] as num?)?.toDouble() ?? 0.15;
          _snapRadius = (settings['snapRadius'] as num?)?.toDouble() ?? 120;
          _dwellTime = (settings['dwellTime'] as num?)?.toInt() ?? 1200;
          _kalmanProcess =
              (settings['kalmanProcess'] as num?)?.toDouble() ?? 0.002;
          _kalmanMeasurement =
              (settings['kalmanMeasurement'] as num?)?.toDouble() ?? 0.05;
        });
      }
    } catch (e) {
      debugPrint('Error loading settings: $e');
    }
    setState(() => _loading = false);
  }

  Future<void> _saveSettings() async {
    try {
      await _settingsChannel.invokeMethod('saveSettings', {
        'headPoseWeight': _headPoseWeight,
        'smoothingFactor': _smoothingFactor,
        'snapRadius': _snapRadius,
        'dwellTime': _dwellTime,
        'kalmanProcess': _kalmanProcess,
        'kalmanMeasurement': _kalmanMeasurement,
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Settings saved! Re-enable Accessibility Service to apply.',
            ),
            backgroundColor: Colors.green,
          ),
        );
      }
    } catch (e) {
      debugPrint('Error saving settings: $e');
    }
  }

  void _resetDefaults() {
    setState(() {
      _headPoseWeight = 0.8;
      _smoothingFactor = 0.15;
      _snapRadius = 120;
      _dwellTime = 1200;
      _kalmanProcess = 0.002;
      _kalmanMeasurement = 0.05;
    });
    _saveSettings();
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Accuracy Settings'),
        backgroundColor: Colors.deepPurple,
        actions: [
          IconButton(
            icon: const Icon(Icons.restore),
            tooltip: 'Reset to Defaults',
            onPressed: _resetDefaults,
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Head Tracking Section
          _buildSectionHeader('Head vs Eye Tracking'),
          _buildSlider(
            title: 'Head Pose Weight',
            subtitle: _headPoseWeight < 0.5
                ? 'More eye-based (precise but sensitive)'
                : 'More head-based (stable but coarse)',
            value: _headPoseWeight,
            min: 0.0,
            max: 1.0,
            divisions: 20,
            label: '${(_headPoseWeight * 100).toInt()}% head',
            onChanged: (v) => setState(() => _headPoseWeight = v),
          ),

          const Divider(height: 32),

          // Smoothing Section
          _buildSectionHeader('Smoothing'),
          _buildSlider(
            title: 'Smoothing Factor',
            subtitle: _smoothingFactor < 0.1
                ? 'Very smooth (laggy)'
                : _smoothingFactor > 0.25
                ? 'Responsive (jittery)'
                : 'Balanced',
            value: _smoothingFactor,
            min: 0.05,
            max: 0.4,
            divisions: 14,
            label: _smoothingFactor.toStringAsFixed(2),
            onChanged: (v) => setState(() => _smoothingFactor = v),
          ),
          _buildSlider(
            title: 'Kalman Process Noise',
            subtitle: 'Lower = smoother but slower response',
            value: _kalmanProcess,
            min: 0.001,
            max: 0.01,
            divisions: 9,
            label: _kalmanProcess.toStringAsFixed(3),
            onChanged: (v) => setState(() => _kalmanProcess = v),
          ),
          _buildSlider(
            title: 'Kalman Measurement Noise',
            subtitle: 'Lower = trusts raw data more',
            value: _kalmanMeasurement,
            min: 0.01,
            max: 0.15,
            divisions: 14,
            label: _kalmanMeasurement.toStringAsFixed(2),
            onChanged: (v) => setState(() => _kalmanMeasurement = v),
          ),

          const Divider(height: 32),

          // Interaction Section
          _buildSectionHeader('Interaction'),
          _buildSlider(
            title: 'Snap Radius',
            subtitle: 'Distance to snap to buttons (pixels)',
            value: _snapRadius,
            min: 50,
            max: 250,
            divisions: 20,
            label: '${_snapRadius.toInt()}px',
            onChanged: (v) => setState(() => _snapRadius = v),
          ),
          _buildSlider(
            title: 'Dwell Time',
            subtitle: 'Time to look before clicking',
            value: _dwellTime.toDouble(),
            min: 500,
            max: 3000,
            divisions: 25,
            label: '${_dwellTime}ms',
            onChanged: (v) => setState(() => _dwellTime = v.toInt()),
          ),

          const SizedBox(height: 32),

          FilledButton.icon(
            onPressed: _saveSettings,
            icon: const Icon(Icons.save),
            label: const Text('Save Settings'),
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 16),
            ),
          ),

          const SizedBox(height: 16),

          Text(
            'Tip: After saving, toggle Accessibility Service OFF then ON to apply changes.',
            style: TextStyle(color: Colors.grey[500], fontSize: 12),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.bold,
          color: Colors.deepPurple,
        ),
      ),
    );
  }

  Widget _buildSlider({
    required String title,
    required String subtitle,
    required double value,
    required double min,
    required double max,
    required int divisions,
    required String label,
    required ValueChanged<double> onChanged,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: const TextStyle(fontWeight: FontWeight.w500)),
        Text(subtitle, style: TextStyle(fontSize: 12, color: Colors.grey[500])),
        Slider(
          value: value.clamp(min, max),
          min: min,
          max: max,
          divisions: divisions,
          label: label,
          onChanged: onChanged,
        ),
      ],
    );
  }
}
