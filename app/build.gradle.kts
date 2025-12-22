import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-kapt") // For Room
}

// Load local.properties
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

// LibGDX version - must match PixelWheels (pinned for tablet stability)
val gdxVersion = "1.9.14"

android {
    namespace = "com.example.arlo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.arlo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // DEV ONLY: Bake API key from local.properties or environment
        val apiKey = localProperties.getProperty("ANTHROPIC_API_KEY")
            ?: System.getenv("ANTHROPIC_API_KEY")
            ?: ""
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$apiKey\"")

        // Kokoro TTS server URL from local.properties
        val kokoroUrl = localProperties.getProperty("KOKORO_SERVER_URL")
            ?: System.getenv("KOKORO_SERVER_URL")
            ?: ""
        buildConfigField("String", "KOKORO_SERVER_URL", "\"$kokoroUrl\"")

        // Only include ARM architectures (Fire tablets) to reduce APK size
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Include PixelWheels assets for the game
    sourceSets {
        named("main") {
            assets.srcDirs("src/main/assets", "../pixelwheels/android/assets")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // HTTP & JSON (for Claude API)
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Security (for API key storage)
    implementation(libs.androidx.security.crypto)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // Image Loading
    implementation(libs.coil)

    // ExoPlayer (for Kokoro TTS audio playback)
    implementation("androidx.media3:media3-exoplayer:1.5.0")

    // Phonetic matching (for speech recognition fuzzy matching)
    implementation("commons-codec:commons-codec:1.17.0")

    // FlexboxLayout for animated word display
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    // PixelWheels game integration (GPL-3.0+)
    implementation(project(":pixelwheels:core"))

    // LibGDX Android backend (matching PixelWheels 1.9.14)
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-box2d:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")

    // LibGDX native libraries - ARM only for Fire tablets
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-arm64-v8a")
    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-armeabi-v7a")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-arm64-v8a")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-armeabi-v7a")

    // LibGDX controllers (used by PixelWheels)
    implementation("com.badlogicgames.gdx-controllers:gdx-controllers-core:2.2.2")
    implementation("com.badlogicgames.gdx-controllers:gdx-controllers-android:2.2.2")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
