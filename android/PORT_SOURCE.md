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

## Prebuilt APK

The installable binary is **not** committed here — `build/` is a generated
directory and a 19 MB artifact does not belong in git history. The build that
corresponds exactly to mirrored commit `2d4dad0` is available separately:

| | |
|---|---|
| File | `app-debug.apk` |
| Size | 19,142,053 bytes |
| MD5 | `e358948c6b95525c2dd6254327ce6732` |
| `applicationId` | `com.hakim3691.bta` |
| `versionCode` | `20` |
| `versionName` | `1.0.20+2d4dad0` |
| `minSdkVersion` | 26 (Android 8.0) |
| `targetSdk` / `compileSdk` | 34 (Android 14) |

`versionName` carries the git commit it was built from, so the string
`1.0.20+2d4dad0` is a quick way to confirm the binary matches this source.

**Caveat for builds made from this branch.** `versionCode`/`versionName` are
derived from git at build time in `app/build.gradle.kts`, but only when the
project directory is itself the root of a git checkout. Here it is not - it sits
inside the Node.js repository - so a build from this branch reports
`1.0.20+nosha` and `versionCode=20` (the pinned fallback in
`gradle.properties`) rather than `1.0.20+2d4dad0`. The `+nosha` suffix is
deliberate: it means "no provenance available", not a borrowed commit id.

Consequences for anyone comparing binaries:

- `versionCode`/`versionName` will **not** match the table above when rebuilt here.
- The md5 will **not** match `e358948c6b95525c2dd6254327ce6732`, because the
  version strings are embedded in the manifest.
- The APK *is* otherwise equivalent - same source, same SDK, same signing scheme.
- The build is reproducible in the sense that matters: the same commit in the
  standalone repository always produces `1.0.20+2d4dad0` and the same md5.

Once this lives in its own repository again, the original provenance behaviour
returns automatically with no build-file change.

To verify a download before installing:

```sh
md5sum app-debug.apk
# expect: e358948c6b95525c2dd6254327ce6732
```

This is a **debug** build signed with the standard Android debug key. It is not
suitable for distribution, and the debug key is machine-local — a release build
requires a proper keystore that is deliberately not part of this repository.
See `SECURITY.md`.

## Building

See `android/README.md`, `android/ARCHITECTURE.md` and `android/TESTING.md`.
`local.properties` (Android SDK location) is generated locally by the IDE or
Gradle and is not committed.

```sh
cd android
./gradlew testDebugUnitTest    # 260 unit tests
./gradlew assembleDebug       # produces app/build/outputs/apk/debug/app-debug.apk
```

Building requires a local Android SDK (`ANDROID_HOME` or `local.properties`);
none of that is mirrored here.
