# Source of this directory

`android/` is a **mirror** of the standalone Android repository, temporarily
stored on a branch of the original Node.js repo so the work is not lost while a
dedicated repository is being set up.

- Canonical repository: `Hakim3691/binance-triangle-arbitrage-android`
- Mirrored branch: `master`
- Mirrored commit: `2d4dad0` — *"Make the Stage 3 evidence trustworthy, and stop
  the app cooking itself"*
- Mirrored on: 2026-09-30

## Rules for this copy

1. **This is not the source of truth.** Make changes in
   `binance-triangle-arbitrage-android`, then re-mirror. Committing directly
   here will be lost the next time the directory is replaced.
2. `android/.git` is intentionally absent — this directory is not an
   independent repository. There is no nested `.git`, and the outer repository
   tracks the files directly.
3. Generated/ignored paths are not mirrored: `build/`, `.gradle/`, `.kotlin/`,
   `.idea/`, `local.properties`. The Android project's own `android/.gitignore`
   is mirrored and keeps them out of commits here too.

## Relationship to the Node.js code

The Android app is a self-contained native port and shares no runtime or build
dependency with the original `src/`. Nothing outside `android/` was changed on
this branch.

## Building

See `android/README.md`, `android/ARCHITECTURE.md` and `android/TESTING.md`.
`local.properties` (Android SDK location) is generated locally by the IDE or
Gradle and is not committed.
