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

    // Cheia de semnare e secretă: vine din secretele GitHub (vezi .github/workflows/android-apk.yml).
    // Fără ea se construiește doar varianta de test (debug), nu una care se poate publica.
    val keystorePath = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // player de muzică
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")

    // conectare la Google Drive
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
