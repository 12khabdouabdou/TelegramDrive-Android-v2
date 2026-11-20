# Telegram Drive — Android

**Unlimited free cloud storage via Telegram.** Mobile counterpart to the
[desktop app](https://github.com/caamer20/Telegram-Drive), with a first-class
**AutoBackup** system that silently syncs device folders to Telegram.

---

## Features

### Core
- **Unlimited storage** — Telegram has no account-level storage cap; 2 GB per file
- **Zero cost** — no third-party servers, no subscriptions
- **Full file browser** — Saved Messages + private channels you own, grid &
  list view, multi-select, search (per-folder + global)
- **Streaming media** — ExoPlayer video with PiP, audio bar, zoomable image viewer,
  PDF renderer — all with auto-download (tap to open, file downloads in background,
  auto-plays when ready)
- **Privacy-first** — no analytics, no telemetry, all data stays between you
  and Telegram

### AutoBackup
- **Folder sync** — pick device folders, set schedule (ON_CHANGE / DAILY / WEEKLY / MANUAL)
- **ZIP mode** — upload each folder as a single .zip file instead of individual files
- **Exclude patterns** — skip files matching patterns (e.g. `*.tmp, Thumbs.db, .DS_Store`)
- **Diff engine** — only uploads new/changed files; strict mode compares SHA-256
- **Conditions** — Wi-Fi only, charging only, battery-not-low
- **WorkManager** — survives process death, foreground service for >5-file batches

### File Management
- **Upload** — single files or entire folders as ZIP (with optional AES-256 password)
- **ZIP naming** — name the ZIP file before uploading
- **Download** — with progress notification, auto-play for media
- **Move / rename / delete** — across folders
- **Star files** — mark favorites for quick access
- **Share links** — generate `t.me/c/...` links for files in channel folders
- **Storage analytics** — dashboard with per-folder, per-type breakdown, dedup detection

### Platform Integration
- **DocumentsProvider** — other apps can browse TelegramDrive files via SAF
- **Share-to-app** — share files from gallery/other apps directly into TelegramDrive
- **Picture-in-Picture** — video continues playing when you leave the app
- **Biometric lock** — fingerprint/face unlock on app return
- **FileProvider** — open files in external apps

### Design
- **Material 3 Expressive** — deep navy + amber glassmorphism aesthetic
- **Glass panels** — semi-transparent surfaces with hairline borders
- **Bento dashboard** — storage status hero, category grid, recent files
- **Glassmorphism bottom nav** — 5 tabs with active dot indicator

---

## Build & Run

### Prerequisites

| Tool | Version |
|------|---------|
| JDK | 17 (required by AGP 8.5) |
| Android SDK | compileSdk 35, build-tools 35.0.0 |
| Android Studio | Hedgehog (2023.1.1) or newer |
| Gradle | 8.9 (wrapper included) |

### First build

```bash
# 1. Tell Gradle where your Android SDK lives
cp local.properties.example local.properties
# Edit local.properties: sdk.dir=/path/to/Android/Sdk

# 2. Build debug APK
./gradlew :app:assembleDebug

# 3. Install on a connected device
./gradlew :app:installDebug
# Or: adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

### TDLib native libraries

This project depends on **TDLight-Java** which ships prebuilt `.so` files for
`arm64-v8a`, `armeabi-v7a`, and `x86_64` via Maven. **No manual TDLib
compilation required.**

If the Maven artifact is unreachable from your network, see
`docs/TDLIB_SETUP.md` for building TDLib from source.

---

## First-run setup

1. **Get Telegram API credentials** — go to
   [my.telegram.org](https://my.telegram.org) → API Development Tools →
   copy your `api_id` and `api_hash`.

2. **Open the app** — you'll be prompted for these credentials on first
   launch. They're stored in `EncryptedSharedPreferences` (AES-256-GCM, master
   key in Android Keystore) and never leave the device.

3. **Log in** — enter your phone number (E.164 format), confirm the OTP
   Telegram sends you, then your 2FA password if your account has it.

4. **Optional: enable AutoBackup** — go to Backup tab, pick
   device folders, choose a target (Saved Messages or a folder), set a
   schedule. Done.

---

## Project structure

```
:app                  — single-activity host, Hilt entry, navigation
:designsystem         — Material 3 Expressive palette, glass panels, editorial components
:core:common          — Result, TgError, dispatchers, extensions, FileBucket, PipController
:core:telegram        — TDLib wrapper (TelegramClient), auth state machine, share links
:core:db              — Room schema (folders, synced_files, sync_log, file_cache)
:core:datastore       — AppPreferences (Preferences DataStore)
:core:security        — KeystoreHelper, BiometricHelper
:core:download        — DownloadManager, DocumentsProvider
:core:zip             — ZipUploader (zip/extract/list)
:core:audio           — AudioPlayerController (Hilt singleton)
:feature:auth         — phone → OTP → 2FA flow
:feature:browser      — HomeScreen, FolderScreen, FileDetailsSheet, GlobalSearch
:feature:upload       — UploadSheet, queue VM, ZIP upload dialog
:feature:download     — DownloadListScreen (transfer queue UI)
:feature:autobackup   — BackupWorker, FolderWatcher, DiffEngine, ExcludePatternMatcher
:feature:player       — ExoPlayer video, audio bar, ImageViewer, PdfViewer
:feature:settings     — theme, cache, biometric, API creds, About
:feature:folders      — create/rename/delete sheets
```

### Module dependency graph

```
                :app
                 │
   ┌─────────────┼─────────────────────┐
   ▼             ▼                     ▼
:designsystem  :core:*               :feature:*
                 │                       │
                 └───────────────────────┘
                         depends on
```

- `:app` is the only application module. It depends on every other module
  to wire Hilt + navigation.
- `:core:*` modules contain no UI and depend only on each other (via `api`
  where appropriate so transitive consumers see the same types).
- `:feature:*` modules depend on `:core:*` and `:designsystem`, never on
  each other. Cross-feature navigation is handled by the `:app` NavHost.
- `:designsystem` has no Android-specific dependencies beyond Compose.

---

## Design system

### Color palette (Material 3 Expressive)

| Token | Color | Usage |
|---|---|---|
| `Background` | `#031524` | Deep navy — app background |
| `Surface` | `#031524` | Same as background for immersive look |
| `Primary` | `#FFD395` | Amber — main accent, buttons, selected states |
| `Secondary` | `#9DCAFF` | Light blue — info, secondary actions |
| `Tertiary` | `#66F3B6` | Mint green — success, verified |
| `Error` | `#FFB4AB` | Red — errors, delete |
| `GlassPanel` | `rgba(38,54,71,0.7)` | Semi-transparent glass surface |

### Components

- `GlassPanel` — glassmorphism surface (semi-transparent + hairline border)
- `EditorialHeader` — large title + optional actions
- `HeroNumber` — large centered number (40-56sp, Black weight)
- `CalloutCard` — tinted-background card for high-emphasis content
- `EditorialListItem` — workhorse row (icon bubble + title/subtitle + trailing)
- `EditorialSwitchRow` — list item with Switch
- `EditorialEmptyState` — centered icon + title + description
- `StoragePieChart` — Canvas donut chart with legend

---

## Testing

```bash
# Unit tests
./gradlew test

# Instrumented tests (need a device or emulator)
./gradlew connectedAndroidTest
```

Coverage:
- `:core:common` — `FileBucket`, `PipController`, extensions
- `:core:telegram` — auth state mapping, max file size constant
- `:core:db` — Room schema (in-memory DB via Robolectric)
- `:core:datastore` — default preference values
- `:feature:autobackup` — `DiffEngine`, `ActiveBackupRegistry`, `BackupOrchestrator`
- `:feature:browser` — `StorageAnalytics`, `ExcludePatternMatcher`

---

## CI

`.github/workflows/build.yml` runs on every push/PR to main:
1. JDK 17 + Android SDK setup
2. `./gradlew test` — all unit tests
3. `./gradlew ktlintCheck` — style check
4. `./gradlew :app:assembleDebug` — APK build
5. Upload APK + test results as artifacts (7-day retention)

---

## Permissions

| Permission | Why | When requested |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Telegram API | Manifest (auto-granted) |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` | Gallery upload, AutoBackup | When user picks photos / enables backup |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Backup + download progress | Declared; requested when backup/download starts |
| `POST_NOTIFICATIONS` (API 33+) | Backup + download result notification | Before first backup/download run |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule WorkManager after reboot | Manifest (auto-granted) |
| `USE_BIOMETRIC` | Biometric lock | When user enables in settings |
| `MANAGE_MEDIA` (optional) | Delete originals after backup | Only if user enables "delete after backup" |

No analytics, no crash reporting.

---

## Troubleshooting

### `UnsatisfiedLinkError: no tdjni in java.library.path`

You're running on an ABI we don't ship natives for. Make sure your device /
emulator is `arm64-v8a`, `armeabi-v7a`, or `x86_64`.

### `androidx.security.crypto.MasterKey$BuildingException`

Clear app data and reinstall — corrupted master key in Keystore.

### Backup notification doesn't appear (Android 13+)

`POST_NOTIFICATIONS` is a runtime permission on API 33+. Grant it in
Settings → Apps → Telegram Drive → Notifications.

### WorkManager doesn't run after reboot

Some OEMs (Xiaomi, Huawei, Oppo, Vivo) aggressively kill background apps.
Add Telegram Drive to the "Auto-start" whitelist. See
[dontkillmyapp.com](https://dontkillmyapp.com/).

---

## License

MIT — same as the desktop app. See [`LICENSE`](LICENSE).

## Credits

- [caamer20/Telegram-Drive](https://github.com/caamer20/Telegram-Drive) — desktop counterpart
- [TDLight-Java](https://github.com/tdlight-team/tdlight-java) — TDLib Java bindings
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI toolkit
