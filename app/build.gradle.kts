import java.security.SecureRandom
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// OpenRouter key: read from the git-ignored local.properties, never from source.
// The key is XOR-masked before it goes into BuildConfig so it is not sitting in
// the APK as a plain string. This is NOT real protection — anything compiled
// into an APK can be extracted — so never share a debug APK built with your key.
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val openRouterKey: String = (localProps.getProperty("OPENROUTER_API_KEY") ?: "").trim()

fun maskKey(key: String): Pair<String, String> {
    if (key.isEmpty()) return "" to ""
    val bytes = key.toByteArray(Charsets.UTF_8)
    val rnd = SecureRandom()
    val mask = ByteArray(bytes.size).also { rnd.nextBytes(it) }
    val masked = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor mask[i].toInt()).toByte() }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    return hex(masked) to hex(mask)
}
val (maskedKey, keyMask) = maskKey(openRouterKey)

android {
    namespace = "com.nethra.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.nethra.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "OR_KEY_DATA", "\"$maskedKey\"")
        buildConfigField("String", "OR_KEY_MASK", "\"$keyMask\"")

        // The iQOO 15 (SM8850) is arm64. Dropping other ABIs keeps the ML Kit
        // and LiteRT-LM native libraries from tripling the APK size.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
        // LiteRT-LM memory-maps its native libraries; keep them uncompressed.
        jniLibs { useLegacyPackaging = false }
    }

    testOptions {
        // android.* calls in code under test return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Camera preview, analysis and recording
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // Command-segment trimming / clean export
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)

    // On-device person detection for framing
    implementation(libs.mlkit.pose)
    implementation(libs.mlkit.face)

    // Cloud (OpenRouter) and optional local Gemma
    implementation(libs.okhttp)
    implementation(libs.litertlm.android)

    // Google sign-in (youtube.upload scope) for direct YouTube uploads
    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
    // Real org.json for JVM tests (the android.jar copy is stubbed).
    testImplementation(libs.json)
}
