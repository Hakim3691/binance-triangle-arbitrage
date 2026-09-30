plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * True only when this Gradle root is the top level of a git repository, i.e. the
 * checkout that actually owns these sources. False when git is missing, when the
 * build runs from an exported source tree, or when the project merely sits inside
 * some other repository - all cases where the surrounding repo's history says
 * nothing about this code.
 */
val isOwnGitCheckout: Boolean = run {
    val topLevel = providers.exec {
        commandLine("git", "rev-parse", "--show-toplevel")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    topLevel.isNotEmpty() && topLevel == rootDir.canonicalPath
}

val gitCommitCount: Int = if (isOwnGitCheckout) {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toIntOrNull() ?: 0
} else 0

val gitShortSha: String = if (isOwnGitCheckout) {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get().trim()
} else ""

// Pinned fallbacks, overridable with -Pbta.versionCode=42 or from gradle.properties.
// Used when there is no git history to derive from, so a source export still gets a
// meaningful, monotonic version instead of a fabricated one.
val pinnedVersionCode: Int = (findProperty("bta.versionCode") as String?)?.toIntOrNull() ?: 20
val pinnedVersionName: String = (findProperty("bta.versionName") as String?) ?: "1.0.20"

val resolvedVersionCode: Int = if (gitCommitCount > 0) gitCommitCount else pinnedVersionCode

val resolvedVersionName: String = when {
    // A real checkout: version tracks its own history, so the same commit always
    // builds to the same version and two commits are always distinguishable.
    gitCommitCount > 0 && gitShortSha.isNotEmpty() -> "1.0.$gitCommitCount+$gitShortSha"
    // No usable history. The suffix admits that rather than borrowing another
    // repository's identity.
    else -> "$pinnedVersionName+nosha"
}

android {
    namespace = "com.hakim3691.bta"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hakim3691.bta"
        minSdk = 26
        targetSdk = 34
        // Derived from git so every build is distinguishable in the launcher.
        // Seventeen APKs all reading "1.0.0" makes it impossible to tell which
        // one is installed.
        //
        // The git lookup is deliberately scoped to THIS project directory. A bare
        // `git rev-parse` walks upwards and happily reports whatever repository
        // happens to contain the build, so building these sources from a copy
        // parked in an unrelated repo stamped the version with that repo's
        // history (1.0.3+a7d3574) instead of this project's. The commit suffix
        // is only added when this directory is itself the repository root;
        // otherwise the build falls back to the pinned bta.version.* properties
        // in gradle.properties rather than inventing a provenance it does not have.
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
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
