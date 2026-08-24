import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Local-only credential, never committed — read from local.properties (gitignored)
// so it never ends up in source. Empty string if unset (personal fork only; not
// part of the upstream PR, so this must not hard-fail a fresh checkout's build).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val geminiApiKey: String = localProps.getProperty("gemini.api.key", "")

android {
    namespace  = "com.openlauncher.app"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId  = "com.openlauncher.app"
        minSdk         = 21
        targetSdk      = 36
        versionCode    = 20
        versionName    = "1.0.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")

        // ONNX Runtime bundles native libs for every ABI by default; this
        // head unit's Unisoc UIS8581A is arm64 only, so the rest is dead
        // weight (was ~20MB of the APK before this).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // Default signing config for normal device testing (restores app visibility)
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Wake-word ONNX models are bundled as raw assets — keep them
    // uncompressed in the APK so asset InputStream sizes (used to detect
    // whether they've already been copied to internal storage) are exact,
    // and so the classifier's external-data companion file loads reliably.
    androidResources {
        noCompress += listOf("onnx", "data")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Network — weather
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // On-device inference for the "Hi Sebastian" wake word (Gemini Live's
    // network path proved unreliable in-car; this runs fully offline)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.13.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests — pure-logic functions only (src/test runs on the local JVM,
    // no Android framework available without Robolectric/instrumentation)
    testImplementation("junit:junit:4.13.2")
}
