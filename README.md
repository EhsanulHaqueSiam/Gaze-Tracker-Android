# Eye Tracker

An Android eye tracking application using MediaPipe FaceLandmarker for iris detection. Enables hands-free interaction through gaze-based navigation and dwell-to-click functionality.

## Features

- **🎯 9-Point Calibration** - Comprehensive training for accurate gaze mapping
- **👁️ Blink Detection** - Pauses tracking during blinks
- **📊 Calibration Quality Score** - Know how good your calibration is
- **🔧 Adjustable Settings** - Tune sensitivity, dwell time, and more
- **♿ Accessibility Service** - Global gaze tracking across all apps
- **📳 Haptic Feedback** - Feel when buttons are selected

## Installation

### From Releases
1. Download the latest APK from [Releases](../../releases)
2. Enable "Install from unknown sources" on your device
3. Install the APK

### From Source
```bash
# Clone the repository
git clone https://github.com/yourusername/eye-tracker.git
cd eye-tracker

# Get dependencies
flutter pub get

# Run on device
flutter run
```

## Usage

1. **Calibrate**: Run the 9-point training calibration
2. **Test**: Verify accuracy in the test screen
3. **Enable**: Turn on the Accessibility Service for global use
4. **Settings**: Adjust parameters for your preferences

## Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Head Pose Weight | Balance between head/eye tracking | 80% |
| Smoothing Factor | Gaze smoothness (lower = smoother) | 0.15 |
| Snap Radius | Distance to snap to buttons | 120px |
| Dwell Time | Time to look before clicking | 1200ms |

## Requirements

- Android 8.0 (API 26) or higher
- Front-facing camera
- Accessibility Service permission for global use

## Tech Stack

- **Flutter** - Cross-platform UI
- **Kotlin** - Native Android components
- **MediaPipe** - Face and iris landmark detection
- **CameraX** - Camera handling

## License

MIT License - see [LICENSE](LICENSE) for details
