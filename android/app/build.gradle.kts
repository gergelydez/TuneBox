plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Pe GitHub Actions numărul versiunii crește la fiecare build, ca actualizările să se instaleze peste cea veche
val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "ro.tunebox.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ro.tunebox.app"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // cheie proprie din secretele GitHub dacă există, altfel cea din repo
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "tunebox.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "tunebox"
            keyAlias = System.getenv("KEY_ALIAS") ?: "tunebox"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "tunebox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // un APK separat pe tip de procesor (yt-dlp + Python + ffmpeg sunt mari)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // biblioteca yt-dlp își despachetează Python/ffmpeg din folderul de biblioteci native
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
