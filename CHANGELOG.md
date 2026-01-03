# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2026-01-04

### Added
- **9-Point Training Calibration** with audio/haptic feedback
- **Calibration Quality Score** (0-100%)
- **Real-time Stability Visualizer** during calibration
- **Blink Detection** - pauses tracking when eyes closed
- **IDW Interpolation** for accurate gaze mapping
- **Head Pose Compensation** (85% head, 15% iris)
- **Settings Screen** with adjustable parameters
- **Accessibility Service** for global gaze tracking
- **Dwell-to-Click** functionality

### Features
- MediaPipe FaceLandmarker for iris tracking
- Kalman filter for smooth gaze output
- Adaptive smoothing based on movement speed
- Button snapping in test screen
- Warm cream background during calibration for face lighting
- Screen wake lock during calibration

### Technical
- Native Kotlin implementation for Android
- Flutter frontend with platform channels
- GitHub Actions CI/CD workflows
