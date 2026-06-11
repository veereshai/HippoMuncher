# 🦛 HippoMuncher

A family-friendly Android game for the **Meta Portal (1st Gen, Android 9 / API 28)** that uses the device's front-facing camera and on-device ML Kit face detection to let you control a hungry hippo with your head — no hands required!

Tilt your head left/right to move the hippo and catch falling fruits. Avoid bombs and muddy boots, or the game ends!

---

## 📸 Gameplay

- **Head tracking controls** — move your head to steer the hippo
- **Catch fruits** (watermelon slices, bananas, stars) to score points
- **Avoid bombs & muddy boots** — eating 3 ends the game
- **Don't drop fruits** — letting 3 fall off screen also ends the game
- **3 difficulty levels** — Easy, Medium, Hard (adjust fall speed & spawn rate)
- **High score tracking** per difficulty level
- **Full sound effects** — background music, fruit/bomb sounds, game over sting
- **Hamburger menu** — recalibrate, change difficulty, reset high score, quit

---

## 📦 Download & Install (Prebuilt APK)

> **Requirements:** Android device with ADB enabled, or a Meta Portal with Developer Mode on.

1. Download the latest APK from the [**Releases**](../../releases/latest) page
2. Connect your device via USB
3. Install via ADB:

```bash
adb install -r HippoMuncher.apk
```

4. Launch the app from your launcher (Nova Launcher recommended on Meta Portal)

---

## 🔨 Building from Source

### Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog or newer |
| JDK | 17 or 21 |
| Android SDK | API 33 (compileSdk), targeting API 28 |
| Gradle | 8.x (wrapper included) |

### Clone the repo

```bash
git clone https://github.com/YOUR_USERNAME/HippoMuncher.git
cd HippoMuncher
```

### Configure local SDK path

Create `local.properties` in the project root (not committed to git):

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

Or let Android Studio generate it automatically when you open the project.

### Build a debug APK (signed, ready to sideload)

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Build a release APK

The release build is configured to sign with the local debug keystore for easy sideloading. No Play Store keystore needed.

```bash
./gradlew assembleRelease -x lintVitalRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

> The `-x lintVitalRelease` flag skips a Play Store lint check that flags `targetSdk 28` — intentional for Meta Portal compatibility.

### Install to connected device

```bash
# Debug build
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release build
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Launch via ADB (useful on Meta Portal which has a locked launcher)

```bash
adb shell am start -n com.family.hippomuncher/.MainActivity
```

---

## 🏗️ Project Structure

```
app/src/main/
├── java/com/family/hippomuncher/
│   ├── MainActivity.kt          # Activity, drawer menu, CameraX setup
│   ├── GameSurfaceView.kt       # Game loop, rendering, physics (TextureView)
│   ├── FaceTrackerAnalyzer.kt   # ML Kit face detection → normalized face coords
│   └── SoundFx.kt               # SoundPool (effects) + MediaPlayer (music)
├── res/
│   ├── layout/activity_main.xml # DrawerLayout wrapping game + nav drawer
│   ├── raw/                     # .ogg sound files
│   └── mipmap-*/                # App icons at all densities
└── AndroidManifest.xml
```

---

## 🎮 How It Works

1. **CameraX** streams frames from the front camera to `FaceTrackerAnalyzer`
2. **ML Kit Face Detection** locates the face and reports its normalized X/Y position and size
3. A **calibration step** captures your neutral head position and scale to use as the origin, and adjusts for lateral offset so the hippo centers on your face
4. The **game loop** (running at ~60 fps on a `TextureView`) lerps the hippo toward the tracked face X position
5. Fruits and bombs spawn at random X positions and fall at speed determined by the selected **difficulty**
6. **Game Over** triggers when 3 bombs/boots are eaten OR 3 fruits are dropped

---

## 📱 Device Notes

This game is specifically designed for the **Meta Portal (1st Gen)**:
- `minSdk 28` / `targetSdk 28` (Android 9)
- Uses **bundled ML Kit** face detection model (no Play Services / GMS download required)
- **TextureView** used instead of SurfaceView so the slide-out drawer menu renders correctly above the game
- Landscape orientation locked

---

## 🪪 License

MIT License — feel free to fork, modify, and share!
