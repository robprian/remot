plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // id("com.google.gms.google-services") // enable with google-services.json
}

// Overridable build configuration (never store secrets here).
//   Local/CI in production pass -PSIGNALING_URL=... (or set the env below).
//   Signing secrets come ONLY from Gradle properties/env supplied outside the repo
//   (GitHub Secrets -> workflow env, or local $HOME/.gradle/gradle.properties).
val signingKeystorePath   = providers.gradleProperty("RELEASE_KEYSTORE_PATH").orElse(systemPropertyOrEnv("RELEASE_KEYSTORE_PATH"))
val signingKeystorePass   = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").orElse(systemPropertyOrEnv("RELEASE_KEYSTORE_PASSWORD"))
val signingKeyAlias       = providers.gradleProperty("RELEASE_KEY_ALIAS").orElse(systemPropertyOrEnv("RELEASE_KEY_ALIAS"))
val signingKeyPass        = providers.gradleProperty("RELEASE_KEY_PASSWORD").orElse(systemPropertyOrEnv("RELEASE_KEY_PASSWORD"))
val signalingUrl          = providers.gradleProperty("SIGNALING_URL").orElse(systemPropertyOrEnv("SIGNALING_URL"))

// Returns a Provider with the value of a system property OR an env var of the same
// name (System.getenv wins for CI env injection; -D and system props work locally).
fun systemPropertyOrEnv(name: String): String {
    return System.getenv(name) ?: (System.getProperty(name) ?: "")
}

android {
    namespace = "com.robrion.remot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robrion.remot"
        minSdk = 25
        targetSdk = 35
        // V/C/P production versioning (see docs/VERSIONING.md):
        // versionCode = V*100000 + C*100 + P  →  V2C001 = 200100
        versionCode = 200100
        versionName = "V2C001"

        // Signaling endpoint, overridable at build time. On CI it is supplied via
        // the SIGNALING_URL secret; locally it defaults to a build-time property.
        // No TURN endpoint or secret is ever compiled into the APK — STUN/TURN
        // credentials are fetched at runtime from the signaling server.
        buildConfigField(
            "String",
            "SIGNALING_URL",
            "\"" + (signalingUrl.getOrElse("ws://SIGNALING_URL_OVERRIDE_ME:8080")) + "\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Single installable universal release APK: no ABI splits / filters are
    // configured, so AGP emits one `app-release.apk` (never split_config.*).
    // See buildTypes.release + the Build workflow which validates this.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Persistent production signing. Requires ALL four values via Gradle property
    // or env (set from GitHub Secrets in CI, or gradle.properties locally). If any
    // are missing the release build FAILS — we never silently sign with the debug
    // key or emit an unsigned production APK.
    signingConfigs {
        create("release") {
            storeFile = signingKeystorePath.getOrNull()?.let { file(it) }
            storePassword = signingKeystorePass.getOrNull()
            keyAlias = signingKeyAlias.getOrNull()
            keyPassword = signingKeyPass.getOrNull()
        }
    }

    buildTypes {
        debug {
            // Local dev: emulator -> host loopback (override in gradle.properties if needed).
            buildConfigField(
                "String",
                "SIGNALING_URL",
                "\"" + (signalingUrl.getOrElse("ws://10.0.2.2:8080")) + "\""
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Only enforce signing when the release APK is actually being assembled,
            // so debug/test/lint tasks still work locally without signing credentials.
            if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
                val required = listOfNotNull(
                    signingKeystorePath.getOrNull(),
                    signingKeystorePass.getOrNull(),
                    signingKeyAlias.getOrNull(),
                    signingKeyPass.getOrNull()
                )
                require(required.size == 4) {
                    "Release signing requires RELEASE_KEYSTORE_PATH, RELEASE_KEYSTORE_PASSWORD, " +
                        "RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD (Gradle property or env). " +
                        "Refusing to build an unsigned/debug-signed production APK."
                }
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    // WebRTC (community prebuilt)
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // Signaling transport
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core + security-crypto (EncryptedSharedPreferences)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // FCM (wake device). Requires google-services plugin + google-services.json.
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // QR generation (session code) + scanning (CameraX + ML Kit barcode)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
}
