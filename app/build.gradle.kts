plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The release key lives outside the repository: these four come from
 * ~/.gradle/gradle.properties (or -P), else from the environment. Without
 * all of them assembleRelease still builds, unsigned (README › Release).
 */
val releaseSigningKeys = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { key ->
        val name = "FRPJUMP_RELEASE_$key"
        providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }
    }
val releaseSigned = releaseSigningKeys.values.all { it != null }

android {
    namespace = "ru.adsrv.frpjump"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.adsrv.frpjump"
        minSdk = 29
        targetSdk = 35
        versionCode = 27
        versionName = "0.3.15"
        // Shown in Settings › App; the binary is built by scripts/build-frpc.sh (FRP_VERSION).
        buildConfigField("String", "FRPC_VERSION", "\"0.70.0\"")
        // Settings › Version looks for newer releases here (public GitHub Releases, no token).
        buildConfigField("String", "UPDATE_REPOSITORY", "\"aadegtyarev/frp-jump-android\"")
    }

    signingConfigs {
        if (releaseSigned) {
            create("release") {
                storeFile = file(releaseSigningKeys.getValue("STORE_FILE")!!)
                storePassword = releaseSigningKeys.getValue("STORE_PASSWORD")
                keyAlias = releaseSigningKeys.getValue("KEY_ALIAS")
                keyPassword = releaseSigningKeys.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // x86_64 too, for the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // Phones only: frpc for x86_64 would double the APK.
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigned) signingConfigs.getByName("release") else null
        }
        // The release as R8 makes it (shrunk and renamed, not debuggable), but signed with the debug key
        // and with x86_64 too: installs over a debug build, so the R8 rules (Sora, tm4e, Gson reflection)
        // can be checked on a phone or the emulator (./gradlew installMinified).
        create("minified") {
            initWith(getByName("release"))
            ndk { abiFilters += listOf("x86_64") }
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
            // Netty's jars (under the MQTT client) each carry a JAR index and a version list; neither is read on Android.
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

if (!releaseSigned && gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
    logger.quiet(
        "frp-jump: no release signing key (FRPJUMP_RELEASE_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD " +
            "in ~/.gradle/gradle.properties or the environment); the release APK is built unsigned " +
            "(app-release-unsigned.apk) and cannot be installed as it is.",
    )
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
    // The MQTT explorer: MQTT 5 and 3.1.1 (docs/mqtt-contract.md).
    implementation(libs.hivemq.mqtt.client)
    // The file viewer: Sora Editor with TextMate grammars (LGPL-2.1, docs/files-contract.md › 5).
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
