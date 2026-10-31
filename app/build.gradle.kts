plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ru.adsrv.frpjump"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.adsrv.frpjump"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0-beta1"
        // Shown in Settings › App; the binary is built by scripts/build-frpc.sh (FRP_VERSION).
        buildConfigField("String", "FRPC_VERSION", "\"0.70.0\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testOptions {
        // android.util.Log and friends return defaults in JVM tests instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // Extract libfrpc.so to nativeLibraryDir: Android only allows exec from there.
        jniLibs {
            useLegacyPackaging = true
        }
        // BouncyCastle jars each carry their own OSGi manifests; none is needed on Android.
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Per-app language (Settings > Language) on API 29-32 as well: AppCompatDelegate.setApplicationLocales.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.sshj)
    // The full BouncyCastle provider replaces Android's stripped "BC" (ed25519, curve25519).
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)
    // QR enroll: CameraX for the preview and frames, ZXing core to decode them (no Play Services).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // Settings › Unlock with fingerprint: BiometricPrompt with the screen lock as the fallback.
    implementation(libs.androidx.biometric)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
