import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Everything here comes from the environment so the GitHub Actions workflow can stamp each build.
val appVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val appVersionName = System.getenv("VERSION_NAME") ?: "1.0.6"

android {
    namespace = "dev.opencode.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.opencode.mobile"
        minSdk = 26
        // 34: the foreground service must declare a type, but edge-to-edge is not forced, which keeps the WebView layout simple.
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        // The bundled Linux userland and the OpenCode binary are aarch64 only.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "UPSTREAM_REF", "\"${System.getenv("UPSTREAM_REF") ?: "unknown"}\"")
        buildConfigField("String", "UPSTREAM_VERSION", "\"${System.getenv("UPSTREAM_VERSION") ?: "unknown"}\"")
        // Build time of the runtime bundled in the app, in seconds. An over-the-air build has to be newer than this.
        buildConfigField("long", "RUNTIME_SEQ", "${System.getenv("RUNTIME_SEQ") ?: "0"}L")
        // owner/name of the repository that publishes signed updates. CI fills it in; empty disables update checks.
        buildConfigField("String", "OTA_REPO", "\"${System.getenv("OTA_REPO") ?: "samartsevmaksim5-ops/OpenCode-Mobile"}\"")
        buildConfigField("boolean", "WEBVIEW_DEBUG", System.getenv("WEBVIEW_DEBUG") ?: "false")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // A fixed key keeps every CI build installable over the previous one. Override it with your own via the
        // ANDROID_KEYSTORE_* variables (see README).
        create("release") {
            storeFile = file(System.getenv("ANDROID_KEYSTORE_FILE") ?: "dev.keystore")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "111111"
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "opencode-dev"
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "111111"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            // proot and its loader are executed straight from the native library directory, so they must be
            // extracted to disk instead of being mapped out of the APK.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    androidResources {
        // The root filesystem is large; storing it uncompressed lets the app open it as a file descriptor.
        noCompress += listOf("tgz", "stamp")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
