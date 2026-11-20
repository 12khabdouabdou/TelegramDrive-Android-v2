# Changelog

All notable changes to Telegram Drive for Android are documented here.

---

## [Unreleased — v2.1.2 video thumbnails + password-zip fix]

### Fixed — Bugs
- **Can't open videos/pictures from password-protected ZIP** — root cause:
  `BrowserModule.extractSingleEntry` returned `File(outputDir, safeName)`
  where `safeName = entryName.substringAfterLast('/')`. But zip4j's
  `extractFile(header, destPath)` preserves the entry's internal path —
  extracting `subfolder/photo.jpg` to `destPath/subfolder/photo.jpg`, not
  `destPath/photo.jpg`. The returned path didn't exist → external app
  silently failed to open → user saw nothing. Fix: return
  `File(outputDir, header.fileName)` (the path zip4j actually extracts to).
  Affects both password and non-password zips with nested entries; password
  zips were more affected because the user's test password zips happened to
  have nested structure.

### Fixed — Folder thumbnails (videos)
- **Videos don't have thumbnails in folder** — root cause: thumbnail polling
  in `FolderViewModel.refresh()` stopped after 4 seconds (only polled at 2s
  and 4s). On slower connections, TDLib's thumbnail download hadn't
  completed by then, so the thumbnail never got upserted to Room. Fix:
  extended the polling series to 2s → 4s → 8s → 16s → 32s (~1 minute total),
  with early exit once every thumbnailable entry has a thumbnail.
- **Thumbnail download priority too low** — `TdLibTelegramClient.fetchMessages`
  was requesting thumbnails with TDLib priority 1 (minimum), so they
  downloaded last behind any file download. Bumped to priority 5 so small
  thumbnails preempt large file downloads without starving them.

### Added — Video thumbnails in ZIP browser
- **Video entries in the ZIP browser now show frame thumbnails** — the row
  shows the first frame of the video (extracted via `MediaMetadataRetriever`)
  instead of the generic file-type icon. Supported formats: mp4, m4v, mkv,
  mov, webm, avi, 3gp.
- `ZipThumbnailGenerator` now accepts an injected `VideoFrameExtractor`
  (default: `DefaultVideoFrameExtractor` using `MediaMetadataRetriever`).
  Tests inject a fake extractor — no need to mock the native MediaMetadataRetriever.
- Video frame extraction flow:
  1. Extract entry to a temp file in `java.io.tmpdir` (with `tgdrive_thumb_` prefix)
  2. Call `MediaMetadataRetriever.getFrameAtTime(0, OPTION_CLOSEST_SYNC)`
  3. Downsample if the frame exceeds the 200px target
  4. Delete the temp file (always — even on failure)
- Encrypted zips: video thumbnails return null (same as image thumbnails) —
  the temp-file extraction uses `java.util.zip.ZipFile` which can't read
  AES-encrypted entries. The UI already gates thumbnails off for password
  zips (`showThumbnail = !passwordRequired && !entry.isEncrypted`), so this
  is consistent.

### Added — Tests
- `BrowserModuleZipExtractorTest` — 6 Robolectric tests covering flat/nested
  entries with/without password, wrong password, missing entry. The nested-
  entry tests are the regression for the password-zip bug.
- `ZipThumbnailGeneratorTest` — added 7 new tests for video thumbnails:
  video entry returns bitmap via injected extractor, extractor not called
  for non-video extensions, missing zip / missing entry returns null,
  extractor returning null is cached, video thumbnail cache hit (extractor
  called once), temp file deleted after extraction.

### Notes — Architecture
- `VideoFrameExtractor` is a `fun interface` (SAM) — production code uses
  `DefaultVideoFrameExtractor`, tests pass a lambda. Keeps the generator
  testable without Robolectric's `ShadowMediaMetadataRetriever` (which is
  finicky to configure).
- The `BrowserModuleZipExtractorTest` mirrors `BrowserModule.extractSingleEntry`
  in a private function (the production function is private). If they drift,
  the test stops testing the real code. A future refactor should extract
  `extractSingleEntry` to a top-level function so both production and test
  call the same code.

### Notes — Deferred
- PDF thumbnails in ZIP browser: `PdfRenderer` only accepts a file
  descriptor (not a stream), same constraint as `MediaMetadataRetriever`.
  Would need the same temp-file + extract pattern. Not added in this
  session — image + video cover the common cases.
- `EntryLeading` still uses `Dispatchers.IO` directly (matches existing
  pattern in `PdfViewerScreen.kt:165`). Documented in v2.1.1 changelog.

---

## [Unreleased — v2.1.1 ZIP browser fixes + thumbnails]

### Fixed — Bugs
- **Videos, images, and PDFs not opening from ZIP browser** — root cause:
  `file_paths.xml` declared `<cache-path>` for `shared/` but NOT for
  `zip_extracts/`. `ZipBrowserViewModel.extractEntry()` writes to
  `cacheDir/zip_extracts/`, so `FileProvider.getUriForFile()` threw
  `IllegalArgumentException` and the catch block silently swallowed it.
  User saw nothing happen. Fix: added `<cache-path name="zip_extracts"
  path="zip_extracts/" />` to `file_paths.xml`.

### Changed — ZIP browser open behavior
- **Images now open in-app** via `ImageViewer` (zoom + pan + swipe) instead
  of leaving the app to an external gallery. Videos, PDFs, audio, and other
  types still go through `ACTION_VIEW` (now that FileProvider works).
- Routing decision moved to a pure, tested function: `routeForExtractedFile()`
  in `ExtractedFileRouter.kt`. Same pattern as the existing `FilePreviewRouter`.

### Added — Thumbnails
- **Image entry thumbnails in the ZIP browser** — for each image entry
  (jpg/png/gif/webp/bmp/heif/avif), the row shows a 40dp thumbnail decoded
  straight from the ZIP input stream (no temp file, no full extraction).
  Non-image entries and encrypted zips fall back to the file-type icon.
- `ZipThumbnailGenerator` — image decoder with an in-memory `LruCache`
  (64 entries). Downsamples large images to ≤200px on the longest side
  using `BitmapFactory.Options.inSampleSize`. Two-pass decode (bounds
  first, then real) minimizes memory.

### Added — Tests
- `ExtractedFileRouterTest` — 6 tests covering image → ImageView,
  pdf → External, video/audio → External, unknown → External with `*/*`,
  case-insensitive extension matching.
- `ZipThumbnailGeneratorTest` — 8 Robolectric tests covering non-image
  short-circuit, directory entries, real image decode, downsampling,
  cache hit (same instance), missing zip, missing entry, `clear()`.

### Added — Build infrastructure
- `:feature:browser` now depends on Robolectric + `androidx.test.core`
  + `junit5-vintage-engine` (needed to run JUnit4-style Robolectric tests
  on the JUnit5 platform the project uses globally).
- `:feature:browser` `testOptions.unitTests.isIncludeAndroidResources = true`
  — required by Robolectric for resource resolution.

### Notes — Deferred
- Video/PDF thumbnails: `MediaMetadataRetriever` (video) and `PdfRenderer`
  (PDF) only accept file paths, not streams — would require extracting each
  entry to a temp file first. Image thumbnails cover the common case
  (photo albums); video/PDF left for a follow-up.
- `EntryLeading` uses `Dispatchers.IO` directly (not injected
  `DispatchersProvider`). Matches existing pattern in `PdfViewerScreen.kt`
  line 165. Per `docs/HANDOFF.md` deferred item #15, the codebase has 3
  known spots; this would be a 4th. Architecture-wide refactor, not fixed
  in this session.

---

## [Unreleased — v2.1.0 ZIP browser preview]

### Added — Features
- **ZIP file browser** — tap a `.zip` in any folder to see its contents inline,
  without leaving the app or downloading external tools. Entries are listed
  in a glassmorphism list with file/folder icons, compressed + uncompressed
  sizes, and an encrypted indicator.
- **Single-entry extraction** — tap any file entry inside a ZIP to extract
  just that entry to `cacheDir/zip_extracts/` and open it in the appropriate
  external app via `FileProvider`. Avoids extracting the whole archive.
- **Password-protected ZIP support** — when an encrypted entry is tapped,
  a password dialog is shown. The password is passed to `ZipExtractor` and
  never persisted.
- **Auto-download for ZIPs** — like videos and PDFs, tapping an undownloaded
  ZIP opens the browser immediately and downloads in the background; the
  entry list populates as soon as the file is ready.

### Added — Architecture
- **`ZipEntryInfo`** — richer entry type in `:core:zip` carrying
  `name`, `isDirectory`, `compressedSize`, `uncompressedSize`, `isEncrypted`.
  Added alongside the existing `listZipEntries()` (kept for backward compat).
- **`ZipLister` / `ZipExtractor` functional interfaces** in `:feature:browser`
  — thin seams over `ZipUploader` so the ViewModel can be tested with
  in-memory fakes instead of real zip4j calls.
- **`BrowserModule`** — Hilt module binding `ZipLister` and `ZipExtractor`
  to the production `ZipUploader` singleton.
- **`FilePreviewRoute.Zip`** — new route in `FilePreviewRouter`; zips are
  routed to the in-app browser instead of `ACTION_VIEW`.

### Added — Tests
- `ZipBrowserViewModelTest` — 7 tests covering initial state, entry loading,
  encrypted-entry detection, lister failure, single-entry extraction,
  extractor failure, and `reset()`.

### Changed — Navigation
- `FolderScreen` accepts `onOpenZip: (Long) -> Unit` callback (defaulted to
  no-op so existing call sites don't break).
- `handleFileTap` routes `.zip` files to `onOpenZip` instead of the external
  app fallback.
- `MainBottomNav` threads `onOpenZip` through to `FolderScreen`.
- `RootNavHost` registers `zip/{msgId}` route → `ZipBrowserScreen`.

### Changed — Strings
- 14 new `<string>` entries in `feature/browser/src/main/res/values/strings.xml`
  for the ZIP browser UI (title, states, password dialog, buttons). All new
  text uses `stringResource()` — no new hardcoded strings introduced.

### Build environment note
- JDK 17 not available on the build server; switched to JDK 21 (AGP 8.5
  supports JDK 17–21). Build verified green on JDK 21.

---

## [v2.0.0] — 2026-06-28

### Complete redesign + architecture overhaul + 15+ new features

This release transforms the app from v1.0.3 into a v2.0 with a new design
system, restructured architecture, and major feature additions.

### Added — Design system
- **Material 3 Expressive palette** — deep navy (`#031524`) + amber (`#FFD395`) + mint (`#66F3B6`)
- **GlassPanel component** — glassmorphism surface (semi-transparent + hairline border)
- **Editorial component library** — `EditorialHeader`, `HeroNumber`, `CalloutCard`, `EditorialListItem`, `EditorialSwitchRow`, `EditorialEmptyState`, `EditorialProgressRow`, `EditorialKeyValueRow`, `EditorialRadioRow`, `StoragePieChart`
- **Glassmorphism bottom nav** — 5 tabs (Files/Folders/Transfers/Backup/Settings) with active dot indicator

### Added — Features
- **Storage Analytics dashboard** — per-folder, per-type breakdown, dedup detection, backup coverage, donut chart
- **Global search** — search across ALL chats (not just per-folder)
- **Share-to-app intent** — share files from gallery/other apps into TelegramDrive
- **Picture-in-Picture** — video continues playing when leaving the app
- **DocumentsProvider** — other apps can browse TelegramDrive files via SAF
- **ZIP naming** — name the ZIP file before uploading
- **AutoBackup ZIP mode** — upload each folder as a single .zip
- **Exclude patterns** — skip files matching patterns (e.g. `*.tmp, Thumbs.db`)
- **Telegram link sharing** — generate `t.me/c/...` links for channel files
- **Auto-download for all media** — tap video/image/PDF/audio → opens immediately → downloads in background → auto-plays when ready
- **Batch download** — "Download all" button in selection mode
- **Download progress notification** — system notification with filename + percentage
- **ZIP extract + list** — `ZipUploader.extractZip()` + `listZipEntries()`
- **File details via 3-dot menu** — accessible from file list rows
- **Multi-selection** — long-press enters selection mode, tap toggles selection

### Added — Architecture
- **`:core:download` module** — `DownloadManager` promoted from `:feature:download`
- **`:core:zip` module** — `ZipUploader` promoted from `:feature:upload`
- **`:core:audio` module** — `AudioPlayerController` (Hilt singleton, replaced file-level `private var`)
- **`FileBucket` canonical classifier** — single source of truth for file-type classification in `:core:common`
- **`PipController`** — Hilt singleton bridging Compose ↔ Activity for PiP
- **`ExcludePatternMatcher`** — pure-logic pattern matcher for backup exclusions
- **`send()` removed from public interface** — TdApi types no longer leak through the seam
- **TdLibTelegramClient section markers** — Session / Chats / FileTransfer extraction plan documented
- **CI workflow** — `.github/workflows/build.yml` (test + ktlint + APK build + artifact upload)
- **ktlint configured** — `./gradlew ktlintCheck` works for the first time

### Added — Tests
- `StorageAnalyticsTest` — 15 tests (compute, classify, bucketByType)
- `BackupOrchestratorTest` — 2 mutual-exclusion tests
- `PipControllerTest` — 4 tests
- `HomeScreenUiTest` — 4 real editorial component tests (replaced placeholder)
- `AuthFlowUiTest` — real EditorialHeader test (replaced placeholder)

### Changed — UI/UX
- **All screens redesigned** with Material 3 Expressive glassmorphism aesthetic
- **OnboardingScreen** — HorizontalPager with gradient icon bubbles
- **AuthFlow** — 16dp rounded input fields, teal primary buttons, scrollable
- **HomeScreen** — dashboard bento layout (storage status hero + category grid + recent files)
- **FolderScreen** — glass panel file rows with tinted icon bubbles, 3-dot menu, thumbnails in list
- **DownloadListScreen** — transfer queue with Active/Completed tabs
- **SettingsScreen** — editorial sections with glass panels
- **AutoBackupScreen** — glass panels, status callout, exclude patterns field
- **VideoPlayerScreen** — watches download state, shows progress, auto-plays
- **PdfViewerScreen** — same auto-download pattern as video
- **ImagePlayerScreen** — new screen, same auto-download pattern
- **FileDetailsSheet** — 28dp top corners, share link action
- **AboutScreen** — centered app icon, editorial cards

### Fixed — Bugs
- **Video preview not working** — `handleFileTap` now always opens video player for videos
- **Multi-selection broken** — long-press now enters selection mode (was opening details)
- **FileProvider paths** — added TDLib download paths to `file_paths.xml`
- **Share-to-app declared but not wired** — `MainActivity` now reads `EXTRA_STREAM`
- **CancellationException swallowing** — added re-throw in all suspend catch blocks
- **AudioPlayerBar singleton violation** — replaced file-level `private var` with Hilt singleton
- **Pre-existing test bugs** — fixed `ExtensionsTest`, `TgDriveDatabaseTest`, `TelegramExceptionTest`

### Removed — Dead code
- `Result`/`TgError` module (zero callers, 150 LOC)
- `IdGenerator` (zero usages)
- `FileEncryptor` (zero usages, 159 LOC)

---

## [v1.0.3] — 2026-06-26

### Foundation re-architecture
- EncryptedSharedPreferences recovery path (Samsung Knox fix)
- FileProvider declaration in manifest
- TDLib native loading diagnostics
- Binlog corruption recovery
- Foreground service declaration
- AutoBackup re-architecture (setForeground-first, staging-file-leak prevention)
- CancellationException discipline at all catch sites

---

## [v1.0.0] — 2026-06-23

### Initial release
- File browse (Saved Messages + folders)
- Upload (single + ZIP)
- Download
- AutoBackup (WorkManager + DiffEngine)
- Media player (video + audio + image + PDF)
- Settings (theme, biometric, cache, API creds)
- Biometric lock
- Onboarding flow
