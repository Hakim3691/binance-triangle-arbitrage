plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * True when the git repository found above this directory actually tracks these
 * Android sources.
 *
 * The original check demanded `git rev-parse --show-toplevel == rootDir`, i.e.
 * that android/ be the top level of its own checkout. It never is for the real
 * workflow: the port lives at `android/` inside the Node.js repository, so the
 * toplevel is the repository root and the check was always false. Every APK
 * therefore shipped `Commit: unknown` on the Connection screen, which is the
 * one field that answers "which build am I actually running?" - and it was
 * blank precisely when provenance mattered most.
 *
 * What actually matters is not where the repository root is but whether it
 * tracks THIS code. `git ls-files` on our own build file answers that: it
 * succeeds in the real checkout (android/ is tracked on feature/android-port)
 * and fails in an exported tree dropped inside an unrelated repository, which
 * is the case the check was meant to catch.
 */
val trackedInGitRepo: Boolean = run {
    val topLevel = providers.exec {
        commandLine("git", "rev-parse", "--show-toplevel")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    if (topLevel.isEmpty()) {
        false
    } else {
        // providers.exec runs in the build script's own directory (android/app),
        // so the probe has to run from the Gradle root, where
        // `app/build.gradle.kts` is a real tracked path.
        val tracked = providers.exec {
            workingDir(rootDir)
            commandLine("git", "ls-files", "--error-unmatch", "app/build.gradle.kts")
            isIgnoreExitValue = true
        }
        tracked.result.get().exitValue == 0
    }
}

val gitShortSha: String = if (trackedInGitRepo) {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get().trim()
} else "unknown"

// Version identity is COMMITTED, not derived from git history.
//
// It used to be `1.0.<commit count>+<sha>`, which meant the same source tree
// produced a different APK depending on which repository happened to contain it:
// 1.0.20+2d4dad0 from the standalone repo, 1.0.3+a7d3574 from the mirror inside
// the Node repo, 1.0.20+nosha from an export. Three identities for one piece of
// code makes an md5 check meaningless and makes "which build am I installing?"
// unanswerable.
//
// bta.versionCode / bta.versionName live in gradle.properties and are bumped
// explicitly by whoever changes the code, so every checkout of the same commit
// produces the same APK. The commit is still recorded - as a separate
// BuildConfig field shown under Connection -> Build - so provenance is visible
// without destabilising the version.
val pinnedVersionCode: Int = (findProperty("bta.versionCode") as String?)?.toIntOrNull() ?: 1
val pinnedVersionName: String = (findProperty("bta.versionName") as String?) ?: "1.0.0"

val resolvedVersionCode: Int = pinnedVersionCode
val resolvedVersionName: String = pinnedVersionName
val buildCommit: String = gitShortSha

android {
    namespace = "com.hakim3691.bta"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hakim3691.bta"
        minSdk = 26
        targetSdk = 34
        // Committed in gradle.properties (bta.versionCode / bta.versionName) rather
        // than derived from git, so every checkout of this commit produces an
        // identical APK. Seventeen APKs all reading "1.0.0" is still a real
        // problem, but it is solved by bumping the property when the code changes,
        // not by letting the surrounding repository decide. The commit is
        // reported separately as BuildConfig.BUILD_COMMIT.
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        // Provenance is reported separately from the version, so the committed
        // version stays identical across checkouts while the build still says
        // which commit it came from.
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
