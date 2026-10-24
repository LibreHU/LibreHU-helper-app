import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Version from git: versionName = `git describe` (rolling release tags excluded), versionCode = number of commits.
fun git(vararg args: String): String? =
    try {
        val out = ByteArrayOutputStream()
        val p = ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
        p.inputStream.copyTo(out)
        if (p.waitFor() == 0) out.toString().trim() else null
    } catch (_: Exception) {
        null
    }

android {
    namespace = "org.librehu.helper"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.librehu.helper"
        // The UJC201 runs Android 9.
        minSdk = 28
        targetSdk = 35
        versionCode = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = git("describe", "--tags", "--always", "--dirty", "--exclude", "latest-*", "--exclude", "build-*") ?: "dev"
        // Head units are ARM, 64 or 32-bit; a few old ones (Intel SoFIA) are x86.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }

    // libhwprobe: I²C and serial probes (src/main/cpp), loaded as root by app_process (hw/HwProbe.kt).
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    // Native libraries extracted to nativeLibraryDir: app_process loads libhwprobe.so from there by its path.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    // One signing key for every LibreHU app, so that updates (LibreHU OTA) install over each other: LIBREHU_KEYSTORE*
    // from the CI secrets when set, else keystore/librehu.jks of the repository.
    signingConfigs {
        create("librehu") {
            storeFile = System.getenv("LIBREHU_KEYSTORE")?.let { file(it) } ?: rootProject.file("keystore/librehu.jks")
            storePassword = System.getenv("LIBREHU_KEYSTORE_PASSWORD") ?: "librehu"
            keyAlias = System.getenv("LIBREHU_KEY_ALIAS") ?: "librehu"
            keyPassword = System.getenv("LIBREHU_KEY_PASSWORD") ?: "librehu"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("librehu") }
        // What the CI publishes: not debuggable, so that ART compiles it fully, and optimised by R8 (code and
        // resources shrunk, without renaming: proguard-rules.pro).
        getByName("release") {
            signingConfig = signingConfigs.getByName("librehu")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Lint runs on its own; it must not block the release APK.
        checkReleaseBuilds = false
        // Head unit integration may use permissions granted to privileged installs only.
        disable += setOf("ProtectedPermissions", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
