# DuoCamera

An Android camera app that uses the front and rear cameras at the same time.

## Features

- **Dual photo:** both cameras fire at the same moment and are saved as one image in `Pictures/DuoCamera`.
- **Dual video:** both cameras are recorded into one MP4 in `Movies/DuoCamera`, with sound.
- **Layouts:** tap the layout button to switch between picture-in-picture, top/bottom and side-by-side. **Swap** changes which camera is the main one.
- **Single-camera fallback:** on phones that can't run two cameras at once, photos are taken one after the other and combined in the same layouts. Videos use one camera, and you can tap **Flip** while recording to switch cameras without stopping.

## Build and run

1. Install a recent **Android Studio** (2025.1 or newer).
2. Unzip this folder and choose **File › Open**, then select the `DuoCamera` folder.
3. Let Gradle sync. The first sync downloads Gradle 8.14.3, Android Gradle Plugin 8.13 and CameraX 1.6.1.
4. Plug in your phone with **USB debugging** turned on, then press **Run ▶**.

The minimum is Android 8.0. Running both cameras at once needs Android 11+ and hardware that supports it, which mostly means recent flagships such as Pixel 6 and later or Galaxy S21 and later. A banner at the top tells you when the app is using the single-camera fallback.

> An emulator usually can't run two cameras at once, so test on a real phone.

## Code map

| File | What it does |
|---|---|
| `MainActivity.kt` | Permissions, connecting the cameras for each mode, the shutter, recording, and the controls |
| `PhotoComposer.kt` | Straightens, mirrors and combines the two photos into one image |
| `DualLayout.kt` | The layout options, plus `VideoComposition` (where each camera goes in a dual video) |
| `MediaSaver.kt` | Saves to the gallery through MediaStore |

## Things to check on your phone

- **Video layout positions.** CameraX combines the two cameras in the sensor's landscape frame and rotates the result afterwards. The split-layout values in `VideoComposition` are set up for that. If the halves land in the wrong order or orientation on your phone, swap the signs, or swap the X and Y values, in that one file.
- **Black bars in split videos.** Each half is scaled evenly so nobody looks stretched, which leaves black margins. Photos don't have this problem because they are cropped to fill.
- **PiP corner.** The video inset uses the corner from Google's CameraX sample, which may not match the top-right corner used for photos.

## Automatic builds on GitHub

`.github/workflows/build-apk.yml` builds the app on GitHub's servers every time code is pushed to `main`.
When it finishes (about 5–10 minutes), open the repository's **Releases** page on your phone, download
`DuoCamera-build-N.apk`, open it and allow "Install unknown apps". You can also start a build by hand from the **Actions** tab ("Run workflow").
