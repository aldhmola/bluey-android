plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.bluey"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.bluey.fold"
        minSdk = 30
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // A fixed key checked into the repo, so every build installs over the last one.
        // It only signs your own sideloaded copy; it is not a Play Store key.
        create("bluey") {
            storeFile = rootProject.file("bluey.keystore")
            storePassword = "blueberry"
            keyAlias = "bluey"
            keyPassword = "blueberry"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bluey")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("bluey")
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
        abortOnError = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
