# Shoulder Shield

**Real-time shoulder-surfing protection for Android.** Uses your front camera to detect when someone's looking over your shoulder — then blurs your screen and locks it.

[![Build & Release](https://github.com/its-sujeet/shoulder-shield/actions/workflows/build.yml/badge.svg)](https://github.com/its-sujeet/shoulder-shield/actions/workflows/build.yml)

---

## How It Works

1. **Runs as a foreground service** — persistent, won't be killed by the OS
2. **CameraX captures front camera** — 640×480, throttled to 2 FPS normally, 5 FPS when suspicious
3. **ML Kit analyzes every frame** — detects faces, tracks head orientation, filters false positives
4. **Decision engine** (state machine) decides what to do:
   - **Normal** — you're alone, nothing happens
   - **Stranger alert** — ≥2 faces detected for 3 consecutive frames → full-screen red overlay + notification
   - **Walkaway** — no face for 10 consecutive frames → waits your timeout (3–30s) → dim overlay → locks screen
5. **System overlay** — sits on top of everything, blocks view, doesn't block touch
6. **Screen lock** — via Device Admin or `su` shell (rooted devices)

All processing is on-device. **Zero network calls, zero uploads, zero data leaves your phone.**

---

## Features

### Detection
- Front camera face detection (ML Kit, multi-face tracking with stable IDs)
- Head pose estimation (Euler Y angle — detects if someone's looking at the screen)
- False positive rejection (min face size threshold, confidence filters)
- No enrollment needed — works out of the box

### Privacy Modes
| Mode | Trigger | Response |
|------|---------|----------|
| **Normal** | Single face present | No action |
| **Pending** | No face for ~2s (10 frames) | Nothing visible — buffering |
| **Warning** | Away for configured timeout (3–30s) | Dark overlay "You walked away" |
| **Locking** | Still away after warning | Overlay + screen lock |
| **Stranger Detected** | ≥2 faces for 3 frames | Red overlay "Stranger Watching" |

### Lock Methods (in priority order)
| Method | Requires | Works On |
|--------|----------|----------|
| `DevicePolicyManager.lockNow()` | Device admin enabled | Any Android |
| `su -c input keyevent 26` | Root (Magisk/KernelSU/APatch) | Rooted devices |
| `PowerManager.goToSleep()` | Pre-Android 9 | Legacy devices |
| Overlay-only | Nothing | Fallback — obscures screen but doesn't lock |

### Battery
- 2 FPS when safe, 5 FPS when suspicious
- Camera pauses when screen is off
- Throttled ImageAnalysis — latest frame only, no backlog
- Foreground service with `IMPORTANCE_LOW` notification

### Other
- Configurable away timeout (3s / 5s / 10s / 15s / 30s)
- Manual root toggle in settings (for Magisk/KernelSU users)
- Boot auto-start
- Persistent notification shows current state
- No internet permission — fully offline

---

## Permissions

| Permission | Why |
|------------|-----|
| `CAMERA` | Front camera feed for face detection |
| `FOREGROUND_SERVICE` | Required for background service (Android 14+) |
| `FOREGROUND_SERVICE_CAMERA` | Camera foreground service type |
| `SYSTEM_ALERT_WINDOW` | Draw overlay on top of other apps |
| `POST_NOTIFICATIONS` | Service notification + state updates |
| `WAKE_LOCK` | Keep screen on briefly during overlay |
| `BIND_DEVICE_ADMIN` | Lock screen via Device Policy Manager |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |

Optional:
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — prevents OS from killing the service

---

## Quick Start

### Download
Grab the latest APK from [Releases](https://github.com/its-sujeet/shoulder-shield/releases).

### First Run
1. Install and open
2. Grant **Camera** permission (system dialog)
3. Grant **Overlay** permission (redirects to system settings)
4. Grant **Notifications** permission (system dialog)
5. **Non-rooted** → enable Device Admin when prompted
   **Rooted** → tap Settings → "Device is rooted" toggle → ON
6. Tap **Start Monitoring**
7. Walk away from your phone — watch the overlay kick in

### Building from Source
```bash
git clone https://github.com/its-sujeet/shoulder-shield.git
cd shoulder-shield
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/
```

Requires Android Studio or JDK 17+ with Android SDK 35.

---

## Architecture

```
CameraX (front) → ML Kit Face Detection → FaceAnalyzer
                                              ↓
                                    PrivacyDecisionEngine (state machine)
                                       ↓              ↓
                                 OverlayManager    ScreenLocker
                                       ↓              ↓
                              System Overlay    Device Admin / su
```

### Key Design Decisions
- **Frame-count debounce** over time-based — works at any FPS, no clock drift
- **`START_NOT_STICKY`** — prevents null-intent crash on service restart
- **Callback-based ML Kit** — `addOnCompleteListener` guarantees `ImageProxy.close()` even on exception
- **`TYPE_APPLICATION_OVERLAY`** — the only non-deprecated overlay window type
- **Multi-face priority** — ≥2 faces overrides any absence timer immediately

### State Machine
```
                          ┌─────────────┐
                          │   Normal    │
                          └──────┬──────┘
                 ┌───────────────┼───────────────┐
                 ▼               ▼               ▼
          ┌──────────┐   ┌────────────┐   ┌──────────────┐
          │NoFace    │   │MultiFace   │   │  (single    │
          │Pending   │   │Detected    │   │  face stays)│
          │(10 frames)│   │(1-2 frames)│   └──────────────┘
          └─────┬────┘   └──────┬─────┘
                ▼               ▼
          ┌──────────┐   ┌────────────┐
          │NoFace    │   │MultiFace   │
          │Warning   │   │Alert       │
          │(timeout) │   │(≥3 frames) │
          └─────┬────┘   └──────┬─────┘
                ▼               │
          ┌──────────┐          │
          │NoFace    │◄─────────┘
          │Locking   │  (faces gone → lock)
          └──────────┘
```

---

## Project Structure

```
app/
├── src/main/java/com/privacyguard/
│   ├── camera/
│   │   └── CameraController.kt        — CameraX pipeline
│   ├── engine/
│   │   └── PrivacyDecisionEngine.kt   — State machine
│   ├── ml/
│   │   ├── FaceAnalyzer.kt            — Face data processing
│   │   └── FaceDetectorManager.kt     — ML Kit wrapper
│   ├── overlay/
│   │   └── OverlayManager.kt          — System overlay
│   ├── service/
│   │   └── PrivacyGuardService.kt     — Foreground service
│   ├── system/
│   │   ├── BootReceiver.kt            — Boot auto-start
│   │   ├── PermissionActivity.kt      — Overlay trampoline
│   │   ├── PermissionManager.kt       — Runtime permissions
│   │   └── ScreenLocker.kt            — Device lock + root shell
│   ├── utils/
│   │   ├── Constants.kt               — Config values
│   │   └── PreferencesManager.kt      — DataStore prefs
│   ├── MainActivity.kt                — UI + permission flow
│   └── PrivacyGuardApp.kt             — Application class
└── src/main/res/
    ├── layout/                        — 4 layouts
    ├── values/                        — Strings, colors, themes
    ├── drawable/                      — Adaptive icon
    └── xml/                           — Device admin policy
```

---

## Roadmap

- [ ] Face enrollment — owner vs stranger identification via embeddings
- [ ] PIN/password fallback lock
- [ ] Per-app whitelist (don't shield when using banking/maps)
- [ ] Notification action to snooze
- [ ] On-device ML Kit model download progress
- [ ] Unit tests (UI + state machine coverage)
- [ ] F-Droid release

---

## License

MIT — do whatever you want. If you build something cool, a shoutout's appreciated.
