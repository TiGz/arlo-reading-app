import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-kapt") // For Room
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" // For Supabase SDK
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

// Configuration for LibGDX native libraries (need to be extracted from JARs)
val natives: Configuration by configurations.creating

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

        // Supabase Cloud Sync
        val supabaseUrl = localProperties.getProperty("SUPABASE_URL")
            ?: System.getenv("SUPABASE_URL")
            ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")

        val supabaseAnonKey = localProperties.getProperty("SUPABASE_ANON_KEY")
            ?: System.getenv("SUPABASE_ANON_KEY")
            ?: ""
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")

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

    // Include PixelWheels assets and native libraries for the game
    sourceSets {
        named("main") {
            assets.srcDirs("src/main/assets", "../pixelwheels/android/assets")
            jniLibs.srcDirs("libs")
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

    // Supabase Cloud Sync (2.x for Kotlin 1.9 compatibility)
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.1"))
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.ktor:ktor-client-android:2.3.7")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Kotlinx Serialization (required by Supabase SDK - 1.6.x for Kotlin 1.9)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Lifecycle Process (for app lifecycle observer)
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // PixelWheels game integration (GPL-3.0+)
    implementation(project(":pixelwheels:core"))

    // LibGDX Android backend (matching PixelWheels 1.9.14)
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-box2d:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")

    // LibGDX native libraries - ARM only for Fire tablets
    // These are extracted by the copyAndroidNatives task below
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-armeabi-v7a")

    // LibGDX controllers (used by PixelWheels)
    implementation("com.badlogicgames.gdx-controllers:gdx-controllers-core:2.2.2")
    implementation("com.badlogicgames.gdx-controllers:gdx-controllers-android:2.2.2")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Task to extract LibGDX native libraries from JARs to libs/ directory
// This is required because LibGDX 1.9.14 distributes natives as JAR files
tasks.register("copyAndroidNatives") {
    doFirst {
        val libsDir = file("libs")
        file("libs/arm64-v8a").mkdirs()
        file("libs/armeabi-v7a").mkdirs()

        natives.files.forEach { jar ->
            val outputDir = when {
                jar.name.endsWith("natives-arm64-v8a.jar") -> file("libs/arm64-v8a")
                jar.name.endsWith("natives-armeabi-v7a.jar") -> file("libs/armeabi-v7a")
                else -> null
            }
            if (outputDir != null) {
                copy {
                    from(zipTree(jar))
                    into(outputDir)
                    include("*.so")
                }
            }
        }
    }
}

// Ensure natives are extracted before packaging
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn("copyAndroidNatives")
}
