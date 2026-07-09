# Shoulder Shield — Complete Overhaul Plan

> **Goal:** Turn Shoulder Shield from a barebones face-counter into a polished, Google-tier privacy app with setup wizard, fingerprint unlock, confidence-based scoring, per-app policies, smart trust, intrusion log, decoy screen, and Material You UI.

## Architecture

**Pattern:** Single-activity with fragment-based wizard + foreground service with state-driven overlay management.

**New files needed:**
- `SetupWizardActivity.kt` — Google Pixel-style onboarding wizard (fragments/steps)
- `SetupWizardAdapter.kt` — Page adapter for wizard steps
- `EnrollmentFragment.kt` — Face enrollment with live camera preview + progress ring
- `DashboardActivity.kt` — Main hub after setup: status, log, settings
- `IntrusionLogActivity.kt` — Timeline of protection events with ghost snaps
- `IntrusionLogAdapter.kt` — RecyclerView adapter for log entries
- `IntrusionRepository.kt` — Room DB for intrusion events
- `DecoyScreenManager.kt` — Fake calculator/home screen overlay
- `TrustManager.kt` — WiFi/Bluetooth trust engine
- `AppProtectionManager.kt` — Per-app policy config
- `ConfidenceEngine.kt` — Score-based decision engine (replaces binary state machine)
- `BiometricHelper.kt` — Fingerprint auth wrapper
- `ShoulderShieldService.kt` — Rewrite of PrivacyGuardService
- `ShieldApplication.kt` — App class with Room DB

**Modified files:**
- `PrivacyGuardService.kt` → gutted, logic moves to `ShoulderShieldService.kt`
- `PrivacyDecisionEngine.kt` → replaced by `ConfidenceEngine.kt`
- `OverlayManager.kt` → add decoy screen + biometric prompt overlay
- `ScreenLocker.kt` → add fingerprint-before-lock path
- `FaceDetectorManager.kt` → add confidence output to RecognizedFace
- `PreferencesManager.kt` → add per-app policies, trust configs
- `Constants.kt` → new action names, config values
- `MainActivity.kt` → replace with launcher → setup wizard check

---

## Tasks

### Phase 1: Core Infrastructure

#### Task 1: Create Intrusion room database

**Files:** `app/src/main/java/com/privacyguard/data/`

- `IntrusionEntity.kt` — Room entity (id, timestamp, appPackage, faceCount, strangerSimilarity, ghostSnapPath, triggerReason, confidence)
- `IntrusionDao.kt` — DAO (insert, queryRecent, deleteOlderThan)
- `AppDatabase.kt` — Room DB v1 singleton

#### Task 2: Create ShieldApplication with DB init

**Files:** `PrivacyGuardApp.kt` → rename to `ShieldApplication.kt`

- Initialize Room DB on create
- Update AndroidManifest application name

#### Task 3: Create BiometricHelper

**File:** `app/src/main/java/com/privacyguard/system/BiometricHelper.kt`

- Wraps BiometricPrompt (fingerprint)
- Callbacks: onSuccess, onError, onFailed
- `canAuthenticate()` check
- `authenticate(activity/fragment, title, subtitle)`

---

### Phase 2: Rethink Security Pipeline

#### Task 4: Rewrite ConfidenceEngine

**File:** `app/src/main/java/com/privacyguard/engine/ConfidenceEngine.kt`

Replaces the state machine with a scoring system:

| Factor | Score |
|--------|-------|
| Stranger face | +40 |
| Multiple faces | +50 |
| Owner absent (per second) | +4/sec |
| Direct gaze at screen | +20 |
| Sensitive app (banking) | +30 |
| Trusted WiFi | -50 |
| Trusted BT device | -30 |

**Actions by score:**
- 0–19: No action
- 20–39: Subtle notification pulse
- 40–59: Blur overlay
- 60–79: Privacy shield (full blur + biometric prompt)
- 80–99: Shield + require fingerprint
- 100+: Full device lock

#### Task 5: Create TrustManager

**File:** `app/src/main/java/com/privacyguard/engine/TrustManager.kt`

- Listens for WiFi SSID changes via broadcast receiver
- Listens for Bluetooth device connections
- User-configurable trusted SSIDs and BT MACs
- `isInTrustedEnvironment(): Boolean`
- Trust reduces confidence by 50 points

#### Task 6: Create AppProtectionManager

**File:** `app/src/main/java/com/privacyguard/engine/AppProtectionManager.kt`

- Maps package name → protection level (OFF, LOW, MEDIUM, HIGH, MAXIMUM)
- `getLevelForForegroundApp(context): ProtectionLevel`
- Uses `UsageStatsManager` or `AccessibilityService` to get foreground app
- Default level: MEDIUM. Banking apps auto-detected → MAXIMUM.

---

### Phase 3: Setup Wizard

#### Task 7: Create SetupWizardActivity

**File:** `app/src/main/java/com/privacyguard/SetupWizardActivity.kt`

6-step onboarding with Material You:

| Step | Content |
|------|---------|
| 1 | Welcome + "What is Shoulder Shield" animated explainer |
| 2 | Camera permission card + grant button |
| 3 | Overlay permission card + redirect to settings |
| 4 | Notification permission + grant |
| 5 | Face enrollment with live camera preview + circular progress |
| 6 | Done! "You're protected" with fingerprint setup suggestion |

- Uses ViewPager2 + horizontal dots indicator
- Each step advances only when permission is granted
- Skip option on enrollment step

**Layouts:**
- `activity_setup_wizard.xml` — ViewPager2 + bottom bar
- `fragment_setup_welcome.xml`
- `fragment_setup_camera.xml`
- `fragment_setup_overlay.xml`
- `fragment_setup_notification.xml`
- `fragment_setup_enrollment.xml` — CameraX preview + circular progress ring
- `fragment_setup_complete.xml`

#### Task 8: Create EnrollmentFragment with live preview

**File:** `app/src/main/java/com/privacyguard/ml/EnrollmentFragment.kt`

- PreviewView for live camera
- Overlay ring that fills as enrollment progresses (0–100%)
- Text: "Look at the camera" / "12% enrolled" / "Complete!"
- Haptic feedback on completion
- Uses FaceDetectorManager.enrollFrame()

---

### Phase 4: Overhaul Overlay & Lock

#### Task 9: Create DecoyScreenManager

**File:** `app/src/main/java/com/privacyguard/overlay/DecoyScreenManager.kt`

- When triggered, shows a fake calculator or news article overlay
- Options: calculator, weather, blank Notes page, fake home screen
- Double-tap power button or specific gesture dismisses and shows biometric prompt
- If biometric fails → falls through to real lock

**Layouts:**
- `overlay_decoy_calculator.xml`
- `overlay_decoy_notes.xml`

#### Task 10: Add biometric prompt to OverlayManager

**File:** `app/src/main/java/com/privacyguard/overlay/OverlayManager.kt`

- After shield shows, if confidence < 100 → show biometric prompt directly on overlay
- On fingerprint success → dismiss overlay, resume
- On fingerprint failure → lock screen
- On fingerprint error → fall back to PIN

#### Task 11: Update ScreenLocker with biometric-first path

**File:** `app/src/main/java/com/privacyguard/system/ScreenLocker.kt`

- `lock()` now: if admin active → lockNow(). If rooted → su keyevent. If neither → show overlay biometric prompt instead of just false.
- Add `softLockWithBiometric()` that overlays a fingerprint prompt

---

### Phase 5: Dashboard & Log

#### Task 12: Rewrite MainActivity as Dashboard

**File:** `app/src/main/java/com/privacyguard/MainActivity.kt`

Complete rewrite:
- Top card: Current status (Protected / Monitoring / Away)
- Confidence level as a radial gauge
- Last intrusion timestamp
- Quick actions: Pause 15m, Quick Settings tile
- Bottom nav: Status | Logs | Settings
- Material You dynamic colors

**Layout:** `activity_main.xml` — redesigned with CardViews

#### Task 13: Create IntrusionLogActivity

**File:** `app/src/main/java/com/privacyguard/ui/IntrusionLogActivity.kt`

- RecyclerView with intrusion events
- Each card: timestamp, app icon + name, face count, reason, confidence
- Tap to expand: show ghost snap thumbnail, full details
- Swipe to dismiss individual entries
- "Clear all" button
- Biometric-protected access
- Auto-delete after 48h (via WorkManager or on-app-open cleanup)

**Layout:** `activity_intrusion_log.xml` — RecyclerView + empty state

---

### Phase 6: Settings & Config

#### Task 14: Create SettingsFragment

**File:** `app/src/main/java/com/privacyguard/ui/SettingsFragment.kt`

- Per-app protection levels (list installed apps, pick level)
- Trusted WiFi networks (add/remove SSID)
- Trusted Bluetooth devices (discover + add)
- Shield style (blur / AMOLED black / decoy calculator / decoy notes)
- Sensitivity slider (affects confidence thresholds)
- Away timeout (3s–2min)
- Enrollment management (re-enroll face, clear data)
- "Export log" (JSON)

---

### Phase 7: Wire Everything Together

#### Task 15: Rewrite foreground service

**File:** `app/src/main/java/com/privacyguard/service/ShoulderShieldService.kt`

New service that:
1. On start: loads embedding, loads per-app configs, initializes trust manager
2. Per frame: confidence engine scores → decides action
3. Action routing: notify → blur overlay → biometric shield → decoy → device lock
4. On intrusion: save to Room DB, optionally capture ghost snap
5. Poll loop: check if confidence dropped → dismiss shield
6. Notifications: enrollment progress, status updates, intrusion alerts

#### Task 16: Wire MainActivity → SetupWizard → Service

- On app launch: check if enrollment exists. If not → SetupWizard. If yes → Dashboard.
- Dashboard "Start" button launches ShoulderShieldService
- Dashboard "Stop" button stops service
- Dashboard shows live status via bound service or broadcast

---

## Files Summary

| File | Action |
|------|--------|
| `app/src/main/java/com/privacyguard/data/IntrusionEntity.kt` | **Create** |
| `app/src/main/java/com/privacyguard/data/IntrusionDao.kt` | **Create** |
| `app/src/main/java/com/privacyguard/data/AppDatabase.kt` | **Create** |
| `app/src/main/java/com/privacyguard/PrivacyGuardApp.kt` | **Modify** |
| `app/src/main/java/com/privacyguard/system/BiometricHelper.kt` | **Create** |
| `app/src/main/java/com/privacyguard/engine/ConfidenceEngine.kt` | **Create** |
| `app/src/main/java/com/privacyguard/engine/TrustManager.kt` | **Create** |
| `app/src/main/java/com/privacyguard/engine/AppProtectionManager.kt` | **Create** |
| `app/src/main/java/com/privacyguard/SetupWizardActivity.kt` | **Create** |
| `app/src/main/java/com/privacyguard/ml/EnrollmentFragment.kt` | **Create** |
| `app/src/main/java/com/privacyguard/overlay/DecoyScreenManager.kt` | **Create** |
| `app/src/main/java/com/privacyguard/overlay/OverlayManager.kt` | **Modify** |
| `app/src/main/java/com/privacyguard/system/ScreenLocker.kt` | **Modify** |
| `app/src/main/java/com/privacyguard/MainActivity.kt` | **Rewrite** |
| `app/src/main/java/com/privacyguard/ui/IntrusionLogActivity.kt` | **Create** |
| `app/src/main/java/com/privacyguard/ui/SettingsFragment.kt` | **Create** |
| `app/src/main/java/com/privacyguard/service/ShoulderShieldService.kt` | **Create** |
| `app/src/main/java/com/privacyguard/engine/PrivacyDecisionEngine.kt` | **Delete** |
| `app/src/main/java/com/privacyguard/utils/Constants.kt` | **Modify** |
| `app/src/main/java/com/privacyguard/utils/PreferencesManager.kt` | **Modify** |
| `app/src/main/res/layout/*` | **14 new layouts** |

---

## Verification

After each phase, build:
```bash
cd /home/anon/privacyguard && export ANDROID_HOME=$HOME/Android/Sdk && ./gradlew assembleDebug
```

After final phase, install:
```bash
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Risks & Notes

- **Room + DataStore coexistence:** DataStore for preferences (existing), Room for intrusion log (new). Fine, they don't conflict.
- **BiometricPrompt requires FragmentActivity:** SetupWizardActivity and MainActivity must extend `FragmentActivity` (they already do via `AppCompatActivity`).
- **Camera access conflict:** Can't have CameraX + SetupWizard preview + service camera simultaneously. Wizard must stop camera before service starts.
- **Ghost snap permissions:** Need `WRITE_EXTERNAL_STORAGE` (or use app-internal storage) for intrusion snapshots. Use internal `filesDir/` to avoid permission hassle.
- **Foreground app detection:** `UsageStatsManager` requires `PACKAGE_USAGE_STATS` permission. Alternative: check top activity via `AccessibilityService` (requires user enable). Fallback: last-resume-time from package manager.
- **Deferred:** Decoy screen is Phase 6 — lowest priority. Implement as fake calculator overlay only, skip home screen mirroring.
