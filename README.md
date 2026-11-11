# AI Camera Pro

An offline-first Android camera project written in Kotlin.

## Open the project

1. Open this repository's root folder in Android Studio.
2. Use JDK 17 and allow Gradle sync to finish.
3. Run the app on a physical Android device with a camera.
4. The primary target device is OnePlus 8 (Snapdragon 865, Android 13).

## Camera modes

- **Normal** — saves the captured JPEG without the enhancement pipeline.
- **Vivid** — applies adaptive vibrance, tone and restrained sharpening.
- **AI** — analyzes image statistics and selects adaptive classical image-processing parameters.
- **Portrait** — uses on-device ML Kit selfie segmentation for computational background blur, with a no-blur fallback when segmentation is unavailable.

The first-launch mode is AI. Image preferences are stored with DataStore. Captures are saved through MediaStore under Pictures/AI Camera Pro/.

## GitHub Actions build

The Android CI workflow runs lint, unit-test, debug assembly and release assembly on pushes, pull requests and manual dispatches.

To build from GitHub:
1. Open the Actions tab.
2. Select Android CI.
3. Choose Run workflow (or push a commit).
4. Open the completed run and download the ai-camera-pro-build-outputs artifact.

The release variant is intentionally unsigned. Configure your own private signing key in Android Studio or GitHub Actions secrets before publishing. Never commit a keystore or signing password.

## Current implementation scope and limitations

- Scene analysis currently uses image statistics and heuristic classification (portrait, landscape, night, indoor and general); it is not a broad, trained multi-class scene-recognition model.
- The enhancement pipeline uses classical on-device image-processing algorithms. The only integrated ML model is ML Kit Selfie Segmentation.
- Portrait blur is computational and is not optical depth of field.
- This repository does not yet include a full in-app before/after comparison slider, dedicated gallery browser, neural denoising, AI upscaling or depth estimation.
- Visual quality and camera behavior still require testing on the target OnePlus 8 hardware with a representative photo set.

See MODELS_AND_LICENSES.md for model and dependency notes.
