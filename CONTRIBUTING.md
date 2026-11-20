# Contributing to Telegram Drive

Thanks for considering a contribution! This document covers the basics.
For architecture questions, read [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)
first.

---

## Code style

- **Kotlin only.** No Java, no XML layouts (except manifest + resource files).
- **Jetpack Compose only** for UI. No View, Fragment, or ViewGroup.
- **Coroutines + Flow only.** No RxJava.
- **Hilt only** for DI. No manual `object` singletons with state.
- 4-space indent, no tabs.
- Max line length: 120 characters.
- Imports: alphabetical, no wildcards (`import x.*`).

Run `./gradlew ktlintCheck` before pushing. (Configured separately — see
`build.gradle.kts` for the ktlint plugin if added.)

## Branch model

- `main` — always green, always shippable
- `feature/<short-name>` — for new features
- `fix/<short-name>` — for bug fixes

PRs target `main`. Squash-merge on approval; the PR title becomes the commit
message.

## Commit messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(autobackup): add strict SHA-256 diff mode
fix(telegram): handle WaitRegistration auth state
docs: add TDLIB_SETUP.md
chore(deps): bump Compose BOM to 2024.09
```

## Pull request checklist

Before opening a PR, make sure:

- [ ] `./gradlew assembleDebug` succeeds
- [ ] `./gradlew test` succeeds
- [ ] New code has unit tests (DiffEngine-style pure functions are
      especially easy to test)
- [ ] No new lint warnings (`./gradlew lint`)
- [ ] No new permissions added without justification in the PR description
- [ ] No analytics / telemetry introduced (spec §14)
- [ ] Strings are in `strings.xml`, not hardcoded
- [ ] Dimensions are in `dp`/`sp`, never raw pixels
- [ ] `WindowInsets` respected for edge-to-edge

## Architecture rules

- **Features don't depend on each other.** Cross-feature navigation goes
  through `:app`'s NavHost.
- **`core:*` modules don't depend on `feature:*` or `:app`.**
- **No TDLib types in feature modules.** Always map to domain types at the
  `:core:telegram` boundary.
- **`CoroutineWorker`, never `Worker`** — for coroutine context.
- **`DispatchersProvider` injected**, never `Dispatchers.IO` directly. This
  makes tests deterministic.

## Security rules

- **No plaintext secrets.** API credentials, session keys → Keystore /
  EncryptedSharedPreferences only.
- **No third-party network SDKs.** The only network traffic is to Telegram.
- **No `android:allowBackup="true"`.** Already disabled in the manifest.
- **No `INTERNET` permission for non-Telegram traffic.** If you add a feature
  that needs network, justify it in the PR.

## Testing

- **Pure functions:** unit test with JUnit5 + Truth. Aim for 100% coverage
  on `DiffEngine`, `TgError`, `Result`, etc.
- **Room DAOs:** Robolectric + in-memory DB.
- **ViewModels:** Turbine for Flow assertions, MockK for dependencies.
- **Compose UI:** `createComposeRule` for smoke tests; full Hilt integration
  tests for complex flows.

Don't push without running `./gradlew test` locally.

## Reporting bugs

Open an issue with:

1. Device model + Android version
2. App version (from Settings → About)
3. Steps to reproduce
4. Expected vs actual behaviour
5. Logcat output (filter by tag `TGDrive`)

**Do not include screenshots of private chats or file names** — replace with
placeholders.

## License

By contributing, you agree that your contributions are licensed under the MIT
license, same as the rest of the project.
