plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions sets this, so every build installs as an update over the last one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.hatchbot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hatchbot"
        minSdk = 30
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // A fixed key so new builds can be installed over old ones without uninstalling.
    signingConfigs {
        create("release") {
            storeFile = file("hatchbot.keystore")
            storePassword = "hatchbot"
            keyAlias = "hatchbot"
            keyPassword = "hatchbot"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
    }

    // Compress the OpenCV libraries and code inside the APK: 40 MB -> about 17 MB to download.
    packaging {
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
    }
}

dependencies {
    implementation("org.opencv:opencv:4.10.0")
}
