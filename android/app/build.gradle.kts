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

// Reads a value from a Gradle property, then a system property, then an env var of
// the same name. Unset/blank values resolve to null so callers treat them as absent.
fun configValue(vararg names: String): String? {
    for (name in names) {
        val fromProps = providers.gradleProperty(name).orNull
        if (!fromProps.isNullOrBlank()) return fromProps
        val sys = System.getProperty(name)
        if (!sys.isNullOrBlank()) return sys
        val env = System.getenv(name)
        if (!env.isNullOrBlank()) return env
    }
    return null
}

val signingKeystorePath = configValue("RELEASE_KEYSTORE_PATH", "keystorePath")
val signingKeystorePass = configValue("RELEASE_KEYSTORE_PASSWORD", "keystorePassword")
val signingKeyAlias     = configValue("RELEASE_KEY_ALIAS", "keyAlias")
val signingKeyPass      = configValue("RELEASE_KEY_PASSWORD", "keyPassword")
// Server endpoints. CI supplies these ONLY from GitHub Secrets (never hardcoded
// in source). SIGNALING_URL is the primary signaling endpoint; SERVER_URL is a
// secondary alias used when SIGNALING_URL is unset. SERVER_IP is a direct-IP
// fallback for STUN/TURN health probes when the issued hostname is unreachable.
val signalingUrl        = configValue("SIGNALING_URL", "SERVER_URL")
val serverUrl           = configValue("SERVER_URL")
val serverIp            = configValue("SERVER_IP")
// Secondary/backup signaling endpoint. CI supplies these from GitHub Secrets
// so the IP is never baked into source. SERVER_URL_ALT is a full URL (e.g. a
// backup ws server); SERVER_IP_ALT is a bare IP used as a direct-IP fallback.
val serverUrlAlt        = configValue("SERVER_URL_ALT")
val serverIpAlt         = configValue("SERVER_IP_ALT")

android {
    namespace = "com.robrion.remot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robrion.remot"
        minSdk = 25
        targetSdk = 35
        // V/C/P production versioning (see docs/VERSIONING.md):
        // versionCode = V*100000 + C*100 + P  →  V2C004P07 = 200407
        versionCode = 200407
        versionName = "V2C004P07"

        // Signaling endpoint, overridable at build time. On CI it is supplied via
        // the SIGNALING_URL secret; locally it defaults to a build-time property.
        // No TURN endpoint or secret is ever compiled into the APK — STUN/TURN
        // credentials are fetched at runtime from the signaling server.
        buildConfigField(
            "String",
            "SIGNALING_URL",
            "\"" + (signalingUrl ?: "ws://SIGNALING_URL_OVERRIDE_ME:8080") + "\""
        )
        buildConfigField(
            "String",
            "SERVER_URL",
            "\"" + (serverUrl ?: "") + "\""
        )
        buildConfigField(
            "String",
            "SERVER_IP",
            "\"" + (serverIp ?: "") + "\""
        )
        buildConfigField(
            "String",
            "SERVER_URL_ALT",
            "\"" + (serverUrlAlt ?: "") + "\""
        )
        buildConfigField(
            "String",
            "SERVER_IP_ALT",
            "\"" + (serverIpAlt ?: "") + "\""
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

    // Let JVM unit tests call android.* stubs (e.g. android.util.Log) and get
    // default values instead of throwing "not mocked" — so network/failure-path
    // tests here can run without an emulator or Robolectric.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Persistent production signing. Requires ALL four values via Gradle property
    // or env (set from GitHub Secrets in CI, or gradle.properties locally). If any
    // are missing the release build FAILS — we never silently sign with the debug
    // key or emit an unsigned production APK.
    signingConfigs {
        create("release") {
            if (signingKeystorePath != null) {
                storeFile = file(signingKeystorePath)
                storePassword = signingKeystorePass
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPass
            }
        }
    }

    buildTypes {
        debug {
            // Local dev: emulator -> host loopback (override in gradle.properties if needed).
            buildConfigField(
                "String",
                "SIGNALING_URL",
                "\"" + (signalingUrl ?: "ws://10.0.2.2:8080") + "\""
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Only enforce signing when the release APK is actually being assembled,
            // so debug/test/lint tasks still work locally without signing credentials.
            if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
                val required = listOfNotNull(
                    signingKeystorePath,
                    signingKeystorePass,
                    signingKeyAlias,
                    signingKeyPass
                )
                require(required.size == 4) {
                    "Release signing requires RELEASE_KEYSTORE_PATH, RELEASE_KEYSTORE_PASSWORD, " +
                        "RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD (Gradle property or env). " +
                        "Refusing to build an unsigned/debug-signed production APK."
                }
            }
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) signingConfig = releaseSigning
        }
    }
}

dependencies {
    // WebRTC (community prebuilt)
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // Signaling transport
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // On-device HTTP inspector (Chucker) for diagnosing connectivity issues.
    // Note: Chucker does NOT intercept OkHttp WebSockets, so the signaling
    // connection is logged separately by SignalingDebugLog (see Diagnostics).
    // 4.0.0 (minCompileSdk=1) works with this project's compileSdk 35; 4.3.x
    // requires compileSdk 36 and a newer AGP than this project's toolchain.
    implementation("com.github.chuckerteam.chucker:library:4.0.0")

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
    // Real org.json for the JVM unit tests (SignalingMessages builds JSONObject;
    // the android.jar stub is unusable with isReturnDefaultValues=true).
    testImplementation("org.json:json:20231013")
}
