# Spec: AutoBackup + TDLib Upload Re-Architecture

## Objective

**Problem:** The app hard-crashes when the user taps "Run backup now" on Android 14+. The crash has persisted across v0.11.0–v0.11.4 despite multiple targeted fixes. Each fix revealed a new crash, indicating the architecture is too fragile.

**Goal:** Re-architect the crash-prone path (AutoBackup → TDLib upload) with a clean, minimal, defensive design that is stable on Android 14+ and covered by unit tests to prevent regressions.

**User story:** As a user, when I tap "Run backup now," the backup either completes successfully or shows a clear error message — it never silently crashes to the home screen.

## Tech Stack

- Kotlin 2.1.10, Jetpack Compose, Hilt 2.56, Room 2.7, WorkManager 2.9.1
- TDLib (org.drinkless.tdlib)
- JDK 17, Android SDK 35, minSdk 26, targetSdk 35
- JUnit 5 (Jupiter) for unit tests
- Truth for assertions

## Commands

```bash
# Build
JAVA_HOME=/home/z/jdk17 ./gradlew :app:assembleDebug --no-daemon

# Test
JAVA_HOME=/home/z/jdk17 ./gradlew :feature:autobackup:testDebugUnitTest --no-daemon
JAVA_HOME=/home/z/jdk17 ./gradlew :core:telegram:testDebugUnitTest --no-daemon

# Compile only (faster iteration)
JAVA_HOME=/home/z/jdk17 ./gradlew :app:compileDebugKotlin --no-daemon
```

## Project Structure (re-architected modules only)

```
core/telegram/
  TdLibTelegramClient.kt     → Simplified, defensive, @Volatile everywhere
  TelegramClient.kt          → Interface (unchanged)
  TelegramModule.kt          → Hilt DI (unchanged)

feature/autobackup/
  BackupOrchestrator.kt      → setForeground-FIRST, simplified pipeline
  BackupWorker.kt            → Thin wrapper, Android 14+ foreground-safe
  BackupScheduler.kt         → Enqueue + cancel
  BackupNotifier.kt          → Notification helper
  StagingManager.kt          → Staging file lifecycle
  ActiveBackupRegistry.kt    → Mutual exclusion + progress
  FolderScanner.kt           → Suspend, cancellable
  diff/DiffEngine.kt         → Suspend, cancellable, testable
  AutoBackupViewModel.kt     → UI state
  AutoBackupScreen.kt        → UI

feature/autobackup/src/test/
  BackupOrchestratorTest.kt  → NEW: tests for phase transitions, cancellation
  diff/DiffEngineTest.kt     → Updated: tests for suspend compute
  ActiveBackupRegistryTest.kt → NEW: tests for mutual exclusion
```

## Code Style

**Defensive suspend functions — never swallow CancellationException:**

```kotlin
// CORRECT — re-throw CancellationException
suspend fun doWork() {
    try {
        riskyCall()
    } catch (e: CancellationException) {
        throw e  // NEVER swallow
    } catch (t: Throwable) {
        Log.w("doWork failed", t)
        // recover or rethrow as domain exception
    }
}

// WRONG — swallows CancellationException (coroutine anti-pattern)
suspend fun doWork() {
    try { riskyCall() } catch (t: Throwable) { Log.w("failed", t) }
}
```

**@Volatile on all mutable shared state:**

```kotlin
@Volatile private var client: Client? = null
@Volatile private var myUserIdCache: Long? = null
```

**setForeground is mandatory and early:**

```kotlin
// CORRECT — call setForeground BEFORE any work
suspend fun runBackup(...) {
    try { setForeground(...) }  // if this fails, abort
    catch (e: CancellationException) { throw e }
    catch (t: Throwable) { return Failed("Cannot start foreground service") }
    // ... now do the actual work
}
```

## Testing Strategy

- **Framework:** JUnit 5 (Jupiter) + Truth + kotlinx-coroutines-test
- **Location:** `src/test/java/` in each module
- **Coverage targets:**
  - `DiffEngine`: 100% of compute() branches (empty, match, strict, stale)
  - `ActiveBackupRegistry`: tryBegin atomicity, cancellation signaling
  - `BackupOrchestrator`: phase transitions (Idle→Scanning→Diffing→Uploading→Done/Cancelled/Failed)
- **Test levels:**
  - Unit tests: pure logic (diff, registry, phase machine) — no Android dependencies
  - Integration tests: orchestrator with mocked TDLib client (future)
- **What we DON'T test:** TDLib itself (native), Compose UI (manual)

## Boundaries

- **Always do:**
  - Re-throw `CancellationException` in every `catch (Throwable)`
  - Mark mutable shared vars `@Volatile`
  - Call `setForeground` before any heavy work in workers
  - Run unit tests before building the APK
  - Check notification permission before starting foreground work

- **Ask first:**
  - Changing the TDLib session encryption key format (breaks existing sessions)
  - Changing the Room database schema
  - Adding new dependencies

- **Never do:**
  - Commit secrets or API keys
  - Delete the TDLib binlog (user data loss)
  - Swallow `CancellationException`
  - Call `nav.navigate` from a background thread

## Success Criteria

1. **No hard crash** when tapping "Run backup now" on Android 14+
2. **No hard crash** when tapping "Cancel" during a backup
3. **No hard crash** when the backup completes (success or partial)
4. **No hard crash** when the app is backgrounded during a backup
5. **Clear error message** shown in UI if the backup fails (instead of silent crash)
6. **Foreground notification** appears within 1 second of tapping "Run backup now"
7. **Unit tests pass** for DiffEngine, ActiveBackupRegistry, BackupOrchestrator
8. **Build succeeds** without warnings about missing foreground service type

## Open Questions

1. Is the crash a `ForegroundServiceStartNotAllowedException`? (Can't confirm without logcat, but the re-architecture handles this defensively regardless.)
2. Is TDLib itself crashing natively? (If so, the re-architecture isolates it better but can't fix native bugs.)
3. Does the user have POST_NOTIFICATIONS permission granted? (The re-architecture checks this before starting.)

## Re-Architecture Design

### Key principle: setForeground is the FIRST thing, not the last

**Before (crash-prone):** setForeground was called after scan+diff. If scan took >3 minutes, Android killed the worker.

**After (stable):** setForeground is called IMMEDIATELY when runBackup starts. If it fails (permission denied, background restriction), the orchestrator returns `Failed("Cannot start foreground service")` — a clear error, not a crash.

### Key principle: staging files are never deleted on cancellation

**Before:** Staging file deleted in `finally` → TDLib might still be reading it → native crash.

**After:** Staging file deleted ONLY on success. On cancellation/failure, left for `purgeStale()` (1-hour TTL).

### Key principle: TDLib upload is cancelled on coroutine cancellation

**Before:** Coroutine cancelled → TDLib keeps uploading → reads deleted staging file → native crash.

**After:** `catch (CancellationException)` sends `DeleteMessages` to TDLib before re-throwing → TDLib releases the file.

### Key principle: every catch re-throws CancellationException

**Before:** 22+ sites swallowed CancellationException → zombie coroutines → cascading failures.

**After:** Every `catch (Throwable)` is preceded by `catch (CancellationException) { throw e }`.
