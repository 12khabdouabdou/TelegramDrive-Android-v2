# Handoff Document — TelegramDrive-Android v2.0.0

> **Generated:** 2026-06-28
> **Previous version:** v1.0.3
> **Current version:** v2.0.0
> **Session:** Complete UI redesign + architecture overhaul + 15+ features

---

## What was done in this session

### 1. UI/UX redesign (3 rounds)
- **Round 1:** Editorial Magazine design system (Variant A from prototype skill)
- **Round 2:** Redesign of all remaining screens (Onboarding, Auth, FolderScreen, etc.)
- **Round 3:** Material 3 Expressive "Deep Navy + Amber" glassmorphism inspired by 4 HTML mockup files

### 2. Architecture overhaul
- 3 new core modules: `:core:download`, `:core:zip`, `:core:audio`
- `send()` removed from public `TelegramClient` interface
- `FileBucket` canonical classifier in `:core:common`
- `DownloadManager`/`ZipUploader`/`AudioPlayerController` promoted to `:core:*`
- Dead code deleted (`Result`/`TgError`, `IdGenerator`, `FileEncryptor`)
- CancellationException re-throw discipline applied
- ktlint configured, CI workflow created

### 3. Features shipped
- Storage Analytics dashboard (with donut chart + dedup detection)
- Global search across all chats
- Share-to-app intent wired
- Picture-in-Picture
- DocumentsProvider (SAF integration)
- ZIP naming before upload
- AutoBackup ZIP mode
- Exclude patterns for backup
- Telegram link sharing
- Auto-download for all media types (video/image/PDF/audio)
- Batch download
- Download progress notification
- ZIP extract + list entries
- File details via 3-dot menu
- Multi-selection fix

### 4. Bug fixes
- Video preview not working (auto-download + watch state)
- Multi-selection broken (long-press logic)
- FileProvider paths (TDLib download directory)
- Share-to-app declared but not wired
- CancellationException swallowing
- AudioPlayerBar singleton violation
- 3 pre-existing test bugs

---

## Current state

### Build
- **BUILD SUCCESSFUL** — `./gradlew :app:assembleDebug`
- **108 unit tests pass** — `./gradlew test`
- APK: `/home/z/my-project/download/app-arm64-v8a-debug.apk` (37MB)

### Infrastructure
- JDK 17 (Temurin) at `/home/z/jdk17`
- Android SDK at `/home/z/android-sdk` (platform-tools, platforms;android-35, build-tools;35.0.0)
- Gradle 8.9 (wrapper, distribution pre-downloaded)
- `local.properties` points to `/home/z/android-sdk`

### Build commands
```bash
export JAVA_HOME=/home/z/jdk17
export ANDROID_SDK_ROOT=/home/z/android-sdk
export PATH=$JAVA_HOME/bin:$PATH

# Compile only (fast)
./gradlew :app:compileDebugKotlin --no-daemon

# Full APK
./gradlew :app:assembleDebug --no-daemon

# All unit tests
./gradlew test --no-daemon

# ktlint check
./gradlew ktlintCheck --no-daemon
```

---

## Project structure (v2.0.0)

```
:app                  — single-activity, NavHost, 5-tab bottom nav
:designsystem         — MD3 Expressive palette, GlassPanel, editorial components
:core:common          — FileBucket, PipController, extensions, dispatchers
:core:telegram        — TelegramClient interface, TdLibTelegramClient (750 LOC)
:core:db              — Room v3 (folders, synced_files, sync_log, file_cache)
:core:datastore       — AppPreferences (with backupZipFolders, backupExcludePatterns)
:core:security        — KeystoreHelper, BiometricHelper
:core:download        — DownloadManager + DocumentsProvider
:core:zip             — ZipUploader (zip/extract/list)
:core:audio           — AudioPlayerController (Hilt singleton)
:feature:auth         — phone → OTP → 2FA
:feature:browser      — HomeScreen, FolderScreen, FileDetailsSheet, GlobalSearch, StorageAnalytics
:feature:upload       — ZipUploadOptionsDialog (with name field)
:feature:download     — DownloadListScreen (transfer queue)
:feature:autobackup   — BackupOrchestrator (with ZIP mode), DiffEngine, ExcludePatternMatcher
:feature:player       — VideoPlayerScreen, ImagePlayerScreen, PdfViewerScreen, AudioPlayerBar
:feature:settings     — SettingsScreen, CacheSettingsScreen, AboutScreen
:feature:folders      — CreateFolderSheet, RenameFolderSheet, DeleteFolderDialog
```

---

## Deferred items (for future sessions)

### Architecture (from `improve-codebase-architecture` review)
1. **Candidate #2:** Full TdLibTelegramClient split — extract TdLibSession, TdLibFileTransfer, TdLibChats into separate classes. Needs `TdLibBackend` interface to break circular shared-state dependency. Section markers + TODO comments are in place.
2. **Candidate #4:** Repository layer — `FolderRepository`, `FileRepository` between DAOs and ViewModels. Touches every ViewModel.
3. **Gap #4:** WorkManager-backed downloads — mirror the backup design so downloads survive process death.

### Features (from suggestion lists)
4. **ZIP file browser** — tap a .zip to see contents without downloading. `ZipUploader.listZipEntries()` already exists.
5. **Auto-extract ZIP on download** — `ZipUploader.extractZip()` already exists, needs UI toggle + integration.
6. **ZIP password manager** — remember passwords in EncryptedSharedPreferences.
7. **Backup compression options** — zip4j doesn't expose compression level easily; needs investigation.
8. **Scheduled ZIP cleanup** — auto-delete old backup ZIPs after N days. Needs new table or preference.
9. **File integrity check** — verify SHA-256 of downloaded files. Needs schema change to store hash.
10. **Multi-folder ZIP** — combine multiple folders into one ZIP. Low demand.
11. **Wear OS companion** — L effort, needs separate module.
12. **Drag-to-reorder folders** — needs Compose drag gesture handling.
13. **File version history** — needs Room schema migration (v4). Spec says "ask first".

### Code quality
14. **Extract 68 hardcoded strings to strings.xml** — CONTRIBUTING.md says strings should be in resources.
15. **Inject DispatchersProvider** in PdfViewerScreen, FolderScanner, BackupActionReceiver (currently use `Dispatchers.IO` directly).
16. **Real Compose UI tests** — current UI tests use editorial components, not full Hilt integration tests.

---

## Suggested skills for next session

| Skill | When to use |
|---|---|
| `improve-codebase-architecture` | To revisit the deferred architecture candidates (#2, #4) |
| `grilling` | To walk through the design of the TdLibBackend interface (candidate #2) |
| `tdd` | For the Repository layer (candidate #4) — write tests first for FolderRepository |
| `prototype` | For the ZIP file browser UI — generate variants of the ZIP contents view |
| `diagnosing-bugs` | If any new bugs are reported after testing on a real device |
| `review` | After any significant code change — standards + spec check |
| `qa` | For conversational bug reporting → GitHub issues |

---

## Key files to read first

| File | Why |
|---|---|
| `README.md` | Project overview, build instructions, feature list |
| `docs/ARCHITECTURE.md` | Module graph, data flow, design system, security model |
| `docs/CHANGELOG.md` | Full changelog from v1.0.0 to v2.0.0 |
| `docs/REARCHITECTURE_SPEC.md` | The AutoBackup re-architecture spec (v1.0.3) |
| `docs/v1.0_FOUNDATION_SPEC.md` | The foundation fix spec (Samsung Knox, FileProvider, etc.) |
| `docs/architecture-review.html` | The deepening-candidate report from `improve-codebase-architecture` |
| `CONTRIBUTING.md` | Code style rules, architecture rules, testing rules |
| `.editorconfig` | ktlint style rules |
| `worklog.md` | Full work log (in `/home/z/my-project/worklog.md`) |

---

## Known issues

1. **StorageAnalyticsScreen** uses old editorial colors (backward-compat aliases work, but visual style doesn't fully match the new glassmorphism design). Should be redesigned with `GlassPanel` in a future pass.
2. **DocumentsProvider** only shows cached files — files that exist on Telegram but haven't been browsed yet won't appear until the user opens the folder in TelegramDrive.
3. **Share link** only works for channel folders, not Saved Messages (Telegram doesn't support links to Saved Messages).
4. **Gradle daemon** can OOM if running `assembleDebug` + `test` simultaneously on this server. Run them separately.
5. **ktlint** `ignoreFailures=true` — there's existing style drift. Flip to `false` once clean.

---

## How to continue

1. Read this document + `docs/ARCHITECTURE.md`
2. Build the app: `./gradlew :app:assembleDebug`
3. Run tests: `./gradlew test`
4. Pick a deferred item from the list above
5. Use the appropriate skill (`tdd`, `prototype`, `grilling`, `implement`)
6. Build + test after each change
7. Update `docs/CHANGELOG.md` + `worklog.md`
