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

**Version identity is committed, not derived from git.** `bta.versionCode` and
`bta.versionName` live in `android/gradle.properties` and are bumped explicitly
when behaviour changes. They used to be derived from `git rev-list --count` and
`git rev-parse --short`, which meant the same source produced a different APK
depending on which repository contained it (`1.0.20+2d4dad0`, `1.0.3+a7d3574`,
`1.0.20+nosha`). **Every checkout of a commit now yields the same
`versionName`**, so the table above is meaningful whichever copy you build.

The commit is still recorded, but separately, as `BuildConfig.BUILD_COMMIT`
shown under Connection -> Build. It does not affect the version.

**What the md5 does and does not guarantee.** Verified: wiping `build/`,
`.gradle/` and `.kotlin/` and rebuilding in the same directory reproduces a
byte-identical APK. Building the *same commit* from the mirror instead produces
the same `versionName` but a **different md5**, because `BUILD_COMMIT` differs
(`unknown` vs the mirror's sha) and R8's synthetic-lambda class-name hashes
shift with the string pool.

So:
- Same directory + same commit -> identical md5. This is the guarantee.
- Different directory, same commit -> same version, different md5.

Use `versionName` to identify *what code* is installed and the md5 to identify
*which exact file*.

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
./gradlew testDebugUnitTest    # 267 unit tests
./gradlew assembleDebug       # produces app/build/outputs/apk/debug/app-debug.apk
```

Building requires a local Android SDK (`ANDROID_HOME` or `local.properties`);
none of that is mirrored here.

## Keeping the copies in sync

This directory is the **source of truth**. `Hakim3691/binance-triangle-arbitrage-android`
is a generated mirror and the offline package is a build output - neither is
edited by hand.

```sh
tools/sync-and-build.sh
```

One command that commits-check, syncs the mirror, runs the tests, rebuilds the
APK and republishes the offline package with `RESTORE.md` rewritten from the
binary that was actually produced. It refuses to run on a dirty `android/` tree
and refuses to overwrite a dirty mirror, so what lands in the mirror is exactly
what is committed.

Run it after every change. Override the targets with `BTA_MIRROR_DIR` and
`BTA_PACKAGE_DIR`.
