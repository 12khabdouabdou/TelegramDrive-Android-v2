# Architecture

This document describes the high-level structure, data flow, security model,
and design system of Telegram Drive for Android.

---

## Module dependency graph

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
- `:core:*` modules contain no UI and depend only on each other.
  - `:core:common` — Result, TgError, dispatchers, extensions, FileBucket, PipController
  - `:core:telegram` — TDLib wrapper (TelegramClient interface + TdLibTelegramClient impl)
  - `:core:db` — Room schema (v3): folders, synced_files, sync_log, file_cache
  - `:core:datastore` — AppPreferences (Preferences DataStore)
  - `:core:security` — KeystoreHelper, BiometricHelper
  - `:core:download` — DownloadManager (Hilt singleton), DocumentsProvider
  - `:core:zip` — ZipUploader (zip/extract/list)
  - `:core:audio` — AudioPlayerController (Hilt singleton)
- `:feature:*` modules depend on `:core:*` and `:designsystem`, never on
  each other. Cross-feature navigation goes through `:app`'s NavHost.
- `:designsystem` — Material 3 Expressive palette, glass panels, editorial
  components, typography, shapes.

### Key rule: Features don't depend on each other

Per CONTRIBUTING.md. Cross-feature singletons (DownloadManager, ZipUploader,
AudioPlayerController) live in `:core:*` so features depend on core, not
on each other.

---

## Data flow — file browse

```
UI (FolderScreen)
    │ collectAsStateWithLifecycle(state)
    ▼
FolderViewModel (StateFlow<FolderUiState>)
    │ refresh() / bind(chatId)
    ▼
FileCacheDao.observeForChat(chatId)
    │ Room emits cached rows immediately
    ▼ (parallel)
TelegramClient.fetchMessages(chatId, fromMessageId, limit)
    │ sends TDLib GetChatHistory
    ▼
TDLib JNI ←→ Telegram MTProto servers
    │ returns TdApi.Messages
    ▼
FileCacheDao.upsertAll(entities)
    │ Room emits updated rows; UI updates reactively
```

The UI **always** renders from Room, never directly from TDLib.

---

## Data flow — AutoBackup

```
[ PeriodicWorkRequest ]   [ ContentObserver ]   [ User taps "Run now" ]
         │                          │                       │
         └──────────────┬───────────┴───────────────────────┘
                        ▼
                 BackupWorker.doWork()
                        │
       ┌────────────────┼────────────────────┐
       ▼                ▼                    ▼
 read prefs      scan SAF folders      diff against synced_files
 (target,        via FolderScanner    via DiffEngine
  conditions,    (DocumentFile tree)
  exclude
  patterns,
  zip mode)
                        │
                        ▼
              ┌─── prefs.backupZipFolders?
              │
         ┌────┴────┐
         ▼         ▼
    ZIP mode    Individual mode
    (1 zip      (N file uploads
     per           per folder)
     folder)         │
         │              │
         ▼              ▼
    ZipUploader     TelegramClient.uploadFile()
    .zipFolder()       │
         │              ▼
         ▼          SyncedFileDao.upsert(record)
    TelegramClient      │
    .uploadFile()       ▼
         │          setProgress(count, total) → WorkManager → UI
         ▼
    SyncedFileDao.upsert(record)
         │
         ▼
    SyncLogDao.upsert(entry)
         │
         ▼
    BackupNotifier.showResult(...)
```

### Diff engine

`DiffEngine` compares local files against the `synced_files` table to decide
what to upload:

- **Default (non-strict):** skip if `local_path + size` matches an existing
  synced file.
- **Strict mode:** also compare SHA-256.
- **Exclude patterns:** files matching `backupExcludePatterns` are filtered
  out before diffing.

### ZIP mode

When `backupZipFolders` is enabled, `BackupOrchestrator.runZipMode()` zips
each source folder into a single `.zip` and uploads it as one message.
This reduces message count (1 per folder instead of N per folder).

---

## Data flow — file preview (auto-download)

```
User taps a file in FolderScreen
    │
    ▼
handleFileTap(messageId, ...)
    │
    ├── classifyFile(mime, fileName) → FileBucket
    │
    ├── VIDEOS → onOpenVideo(messageId) → VideoPlayerScreen
    │              │ watches downloadManager.state
    │              ▼ file not downloaded? vm.downloadFile(item)
    │              ▼ download completes → exo.setMediaItem() → exo.play()
    │
    ├── IMAGES → onOpenImage(messageId) → ImagePlayerScreen
    │              │ same pattern: watch download, auto-display when ready
    │
    ├── PDF → onOpenPdf(messageId) → PdfViewerScreen
    │           │ same pattern: watch download, auto-render when ready
    │
    ├── AUDIO → vm.downloadAndPlayAudio(item)
    │            │ watches download, auto-plays via AudioPlayerController
    │
    └── OTHER → open in external app (FileProvider) or download
```

---

## Security model

| Asset | Storage | Protection |
|---|---|---|
| Telegram session | TDLib internal DB (`filesDir/tgdrive_tdlib/db/`) | TDLib encryption, key derived from Keystore-protected secret |
| API ID + Hash | `EncryptedSharedPreferences` | AES-256-GCM, master key in Android Keystore (TEE/StrongBox) |
| User preferences | Preferences DataStore (`filesDir/tgdrive_prefs/`) | Android file-system sandboxing |
| Cached files | `cacheDir/downloads/` + TDLib's `filesDir/tgdrive_tdlib/files/` | Sandboxed; cleared with app data |
| Synced files index | Room DB (`tgdrive.db`) | Android file-system sandboxing |
| Biometric unlock | (no secret stored — just a gate) | BiometricPrompt with `DEVICE_CREDENTIAL` fallback |

**No data ever leaves the device except to Telegram's servers.**

---

## Threading model

| Operation | Dispatcher |
|---|---|
| TDLib callbacks | TDLib internal thread → marshalled to `Dispatchers.IO` |
| Repository / Worker I/O | `DispatchersProvider.io` (injected via Hilt) |
| Compose UI | `Dispatchers.Main` (automatic) |
| Diff engine (pure compute) | `Dispatchers.Default` |

`DispatchersProvider` is injected everywhere so tests can swap dispatchers.

---

## Design system

### Color palette (Material 3 Expressive — "Deep Navy + Amber")

Extracted from HTML mockups. See `designsystem/.../theme/Color.kt` for
exact tokens.

| Token | Color | Usage |
|---|---|---|
| `Background` | `#031524` | Deep navy |
| `Primary` | `#FFD395` | Amber — main accent |
| `Secondary` | `#9DCAFF` | Light blue — info |
| `Tertiary` | `#66F3B6` | Mint — success |
| `GlassPanel` | `rgba(38,54,71,0.7)` | Glassmorphism surface |

### Components

- `GlassPanel` — glassmorphism surface (semi-transparent + hairline border)
- `EditorialHeader`, `HeroNumber`, `CalloutCard`, `SectionLabel`
- `EditorialListItem`, `EditorialSwitchRow`, `EditorialRadioRow`
- `EditorialEmptyState`, `EditorialProgressRow`, `EditorialKeyValueRow`
- `StoragePieChart` — Canvas donut chart

### Shapes

| Token | Radius | Usage |
|---|---|---|
| `Small` | 4dp | Chips |
| `Medium` | 8dp | Buttons |
| `Large` | 12dp | List items, icon bubbles |
| `XLarge` | 16dp | Cards, panels |
| `XXLarge` | 24dp | Hero cards, bottom sheets |

---

## Navigation

Single-activity, single-`NavHost` design. The root NavHost has two
destinations:

- `auth` — replaces back stack while TDLib isn't ready
- `main` — `MainBottomNav` with 5 tabs (Files / Folders / Transfers / Backup / Settings)

Full-screen overlays (video player, image viewer, PDF viewer, search,
analytics, backup history) are pushed onto the same NavHost.

### Bottom nav

Custom glassmorphism bar with active dot indicator (matching the HTML
mockup design). 5 tabs:
- **Files** (HomeScreen — dashboard bento layout)
- **Folders** (FoldersManagementScreen)
- **Transfers** (DownloadListScreen — transfer queue with Active/Completed tabs)
- **Backup** (AutoBackupScreen)
- **Settings** (SettingsScreen)

---

## DocumentsProvider

`TelegramDocumentsProvider` exposes cached files to other apps via SAF.
Registered in the manifest with `DOCUMENTS_PROVIDER` intent filter.

Hierarchy:
- Root: "TelegramDrive" (virtual folder)
  - Per-chat folders (Saved Messages + each Telegram channel)
    - Files (from `file_cache` table)

Only files that have been cached (browsed/downloaded) are visible.

---

## Share-to-app intent

`MainActivity` declares `SEND` + `SEND_MULTIPLE` intent filters. When a
file is shared from another app:
1. `RootViewModel.handleShareIntent(intent)` reads `EXTRA_STREAM` URIs
2. `MainBottomNav` shows a "Upload N files?" dialog
3. `RootViewModel.confirmShareUpload()` copies URIs to staging via
   `ContentResolver`, uploads to Saved Messages

---

## Out of scope (v1)

Per spec §14, these are explicitly NOT implemented:

- iOS or web client
- Paid tier or backend infrastructure
- Telegram Bot API usage
- Any analytics or crash reporting

---

## Architecture review

See `docs/architecture-review.html` for the full deepening-candidate report
generated by the `improve-codebase-architecture` skill. Key completed items:

- ✅ `send()` removed from public `TelegramClient` interface
- ✅ `FileBucket` canonical classifier in `:core:common`
- ✅ `DownloadManager` / `ZipUploader` / `AudioPlayerController` promoted to `:core:*`
- ✅ `Result`/`TgError` dead code deleted
- ✅ `BackupOrchestrator` mutual-exclusion tests
- ✅ Repository layer + full TdLibTelegramClient split (deferred — L effort)
