plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.fetchly.android"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.fetchly.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 19
        versionName = "1.4.0"
        ndk { abiFilters += "arm64-v8a" }   // phone only: arm64 (small APK)
    }

    // Signing info comes from your USER-level ~/.gradle/gradle.properties, never from the project.
    val ksPath = providers.gradleProperty("FETCHLY_KEYSTORE").orNull
    if (ksPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storePassword = providers.gradleProperty("FETCHLY_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("FETCHLY_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("FETCHLY_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // yt-dlp's Python and ffmpeg are native files that must be unpacked on the phone.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // English-only app with text built in code: no string resources to translate.
    lint { disable += "SetTextI18n" }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
