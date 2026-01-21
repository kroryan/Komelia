# Komelia - Komga media client (Smart Reader Fork)

> **WORK IN PROGRESS - Speech Balloon Detection System**
>
> This is a fork of Komelia that implements an automatic speech balloon detection and navigation system inspired by
> [Seeneva](https://github.com/Seeneva/seeneva-reader-android). Full credit to the Seeneva project for the
> reference behavior, UX, and ML model approach that guided this implementation.
>
> ## Current Status: BASIC PREVIEW
>
> Balloon navigation is usable, but still limited:
> - Reader modes: Paged and Continuous
> - Scaling: Screen Fit only (recommended)
> - Panels and other scale modes are not wired yet
> - Continuous mode can still miss some balloons depending on page layout
>
> ### Goal
> Implement a Seeneva-style speech balloon reader that:
> 1. Automatically detects speech balloons using YOLOv4-tiny ML model
> 2. Allows navigation through balloons with tap gestures (left/right)
> 3. Shows zoomed balloons as popups with proportional scaling
> 4. Supports both manga (RTL) and western comics (LTR) reading directions
> 5. Continuous mode can pre-index balloons for the current comic and reuse that index on the next open
> 6. Continuous mode refreshes the current page and next page in the background to fill missing balloons

> ### Implementation Notes
> The current flow mirrors Seeneva's behavior: each page is processed by a lightweight ML model, detections are stored
> per page, and taps navigate between balloons before allowing page turns. The overlay computes proportional zoom and
> positions each balloon over its on-page location, rather than centering everything.
>
> In Continuous mode, balloon detections are indexed once and stored on disk for the current comic. This makes
> navigation snappy after the first pass. While reading, the current page and next page are re-checked in the
> background to catch any missed balloons without interrupting navigation.
>
> ### Work Remaining
> - Integrate balloon navigation with other reader modes (Panels, Webtoon, etc.)
> - Support additional scale modes beyond Screen Fit (e.g., Fit Width, Free Scale)
> - Expand testing for edge cases (small balloons, overlapping bubbles, multi-panel pages)
> - Performance tuning and optional caching for large chapters
>
> ### Implementation Files
> - `komelia-ui/src/androidMain/kotlin/snd/komelia/ui/reader/balloon/` - Android TFLite detection
> - `komelia-ui/src/commonMain/kotlin/snd/komelia/ui/reader/balloon/` - Common balloon state/UI
> - `komelia-app/src/androidMain/assets/yolo_seeneva.tflite` - ML model from Seeneva
>
> ### How to Test (Debug Build)
> 1. Build: `./gradlew :komelia-app:assembleDebug`
> 2. Install APK from `komelia-app/build/outputs/apk/debug/`
> 3. Open a comic in Paged or Continuous reader mode
> 4. Tap center to open settings, look for "Smart" toggle
> 5. Enable and tap left/right sides of screen to navigate balloons
> 6. Long-press a balloon to open it directly
>
> **Note:** For the long-press selection, try to ensure the balloon is around the middle of the screen (or slightly above).
> Some Komelia gesture layers can interfere if the balloon is too low on screen.

---

### Downloads:

Komelia is currently in early access testing in Google Play Store.\
Get access by joining google group https://groups.google.com/g/komelia-test \
Then just install app from https://play.google.com/store/apps/details?id=io.github.snd_r.komelia

#### Other Downloads:
- Latest prebuilt release is available at https://github.com/Snd-R/Komelia/releases
- F-Droid https://f-droid.org/packages/io.github.snd_r.komelia/
- AUR package https://aur.archlinux.org/packages/komelia

## Screenshots

<details>
  <summary>Mobile</summary>
   <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Komelia" width="270">  
   <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Komelia" width="270">  
   <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Komelia" width="270">  
   <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Komelia" width="270">  
   <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" alt="Komelia" width="270">  
   <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" alt="Komelia" width="270">  
</details>

<details>
  <summary>Tablet</summary>
   <img src="/fastlane/metadata/android/en-US/images/tenInchScreenshots/1.jpg" alt="Komelia" width="400" height="640">  
   <img src="/fastlane/metadata/android/en-US/images/tenInchScreenshots/2.jpg" alt="Komelia" width="400" height="640">  
   <img src="/fastlane/metadata/android/en-US/images/tenInchScreenshots/3.jpg" alt="Komelia" width="400" height="640">  
   <img src="/fastlane/metadata/android/en-US/images/tenInchScreenshots/4.jpg" alt="Komelia" width="400" height="640">  
   <img src="/fastlane/metadata/android/en-US/images/tenInchScreenshots/5.jpg" alt="Komelia" width="400" height="640">  
   <img src="/fastlane/metadata/android/en-US/images/tenInchScreenshots/6.jpg" alt="Komelia" width="400" height="640">  
</details>

<details>
  <summary>Desktop</summary>
   <img src="/screenshots/1.jpg" alt="Komelia" width="1280">  
   <img src="/screenshots/2.jpg" alt="Komelia" width="1280">  
   <img src="/screenshots/3.jpg" alt="Komelia" width="1280">  
   <img src="/screenshots/4.jpg" alt="Komelia" width="1280">  
   <img src="/screenshots/5.jpg" alt="Komelia" width="1280">  
</details>

[//]: # (![screenshots]&#40;./screenshots/screenshot.jpg&#41;)

## Native libraries build instructions

Android and JVM targets require C and C++ compiler for native libraries as well nodeJs for epub reader build

The recommended way to build native libraries is by using docker images that contain all required build dependencies\
If you want to build with system toolchain and dependencies try running:\
`./gradlew komeliaBuildNonJvmDependencies` (Linux Only)

## Desktop App Build

Requires jdk 17 or higher

To build with docker container, replace <*platform*> placeholder with your target platform\
Available platforms include: `linux-x86_64`, `windows-x86_64`

- `docker build -t komelia-build-<platfrom> . -f ./cmake/<paltform>.Dockerfile `
- `docker run -v .:/build komelia-build-<paltform>`
- `./gradlew <platform>_copyJniLibs` - copy built shared libraries to resource directory that will be bundled with the
  app
- `./gradlew buildWebui` - build and copy epub reader webui (npm is required for build)

Then choose your packaging option:
- `./gradlew :komelia-app:run` to launch desktop app
- `./gradlew :komelia-app:packageReleaseUberJarForCurrentOS` package jar file (output in `komelia-app/build/compose/jars`)
- `./gradlew :komelia-app:packageReleaseDeb` package Linux deb file (output in `komelia-app/build/compose/binaries`)
- `./gradlew :komelia-app:packageReleaseMsi` package Windows msi installer (output in `komelia-app/build/compose/binaries`)

## Android App Build

To build with docker container, replace <*arch*> placeholder with your target architecture\
Available architectures include:  `aarch64`, `armv7a`, `x86_64`, `x86`

- `docker build -t komelia-build-android . -f ./cmake/android.Dockerfile `
- `docker run -v .:/build komelia-build-android <arch>`
- `./gradlew <arch>_copyJniLibs` - copy built shared libraries to resource directory that will be bundled with the app
- `./gradlew buildWebui` - build and copy epub reader webui (npm is required for build)

Then choose app build option:

- `./gradlew :komelia-app:assembleDebug` debug apk build (output in `komelia-app/build/outputs/apk/debug`)
- `./gradlew :komelia-app:assembleRelease` unsigned release apk build (output in
  `komelia-app/build/outputs/apk/release`)

## Komf Extension Build

run`./gradlew :komelia-komf-extension:app:packageExtension` \
output archive will be in `./komelia-komf-extension/app/build/distributions`
