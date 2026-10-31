import java.net.URI

/**
 * Build-time settings that must not live in git: from environment variables (CI fills them from
 * GitHub secrets) or, locally, from ~/.gradle/gradle.properties as heartpact.NAME. Empty when unset.
 */
fun setting(name: String): String =
    providers.environmentVariable(name).orElse(providers.gradleProperty("heartpact.$name")).getOrElse("")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.heartpact"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.heartpact"
        minSdk = 26
        targetSdk = 36
        // The deploy pipeline numbers each release (Play needs every upload's code to be higher).
        versionCode = setting("ANDROID_VERSION_CODE").toIntOrNull() ?: 1
        versionName = setting("ANDROID_VERSION_NAME").ifBlank { "0.1.0" }
        // Firebase Cloud Messaging. Without these the app runs without push and polls instead.
        for (name in listOf("FIREBASE_PROJECT_ID", "FIREBASE_APP_ID", "FIREBASE_API_KEY", "FIREBASE_SENDER_ID")) {
            buildConfigField("String", name, "\"${setting(name)}\"")
        }
        // Google sign-in: the web OAuth client id the server checks ID tokens against. Empty hides the button.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${setting("GOOGLE_WEB_CLIENT_ID")}\"")
        // Play Integrity: the Google Cloud project number linked in Play Console. Empty skips the check.
        buildConfigField("String", "PLAY_INTEGRITY_PROJECT_NUMBER", "\"${setting("PLAY_INTEGRITY_PROJECT_NUMBER")}\"")
    }

    signingConfigs {
        // Release signing from CI secrets: the keystore file (decoded from base64), its passwords and alias.
        val keystore = setting("ANDROID_KEYSTORE_PATH")
        if (keystore.isNotBlank() && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = setting("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = setting("ANDROID_KEY_ALIAS")
                keyPassword = setting("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${property("heartpact.apiBaseUrl.debug")}\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val apiUrl = setting("HEARTPACT_RELEASE_API_URL").ifBlank { property("heartpact.apiBaseUrl.release").toString() }
            buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    androidResources {
        // MediaPipe memory-maps the model, so it must stay uncompressed in the APK.
        noCompress += "task"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Plain JVM tests (the live hub client) may call android.util.Log; let the stubs return defaults.
            isReturnDefaultValues = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // Robolectric fetches its Android runtime jars itself; use the same Central mirror as the build.
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                it.systemProperty("robolectric.dependency.repo.id", "google-central-mirror")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.retrofit)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.signalr)
    // SignalR hands hub payloads over as Gson elements; it only declares Gson at runtime.
    implementation(libs.gson)
    // The location map: MapLibre Native with OpenFreeMap vector tiles (no API key); styled at runtime in ui/map.
    implementation(libs.maplibre)
    // The fused provider for location sharing on phones older than Android 12 (the version billing already brings).
    implementation(libs.play.location)
    implementation(libs.billing.ktx)
    // Plays the 3D icons and the splash: res/raw/heartpact_icons.riv, built in design/rive-icons.
    implementation(libs.rive.android)
    implementation(libs.play.integrity)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.googleid)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.work.runtime)
    implementation(libs.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

// The pose model (about 5.5 MB) is fetched at build time rather than committed.
val poseModel = layout.projectDirectory.file("src/main/assets/pose_landmarker_lite.task")
val downloadPoseModel by tasks.registering {
    description = "Downloads the MediaPipe pose landmarker model into assets."
    outputs.file(poseModel)
    onlyIf { !poseModel.asFile.exists() }
    doLast {
        poseModel.asFile.parentFile.mkdirs()
        val url = URI("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task").toURL()
        val partial = File(poseModel.asFile.path + ".part")
        url.openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        check(partial.length() > 1_000_000) { "Pose model download looks truncated (${partial.length()} bytes)." }
        partial.renameTo(poseModel.asFile)
    }
}
tasks.named("preBuild") { dependsOn(downloadPoseModel) }
