plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "mv.dhivehi.subtitles"
    compileSdk = 34

    defaultConfig {
        applicationId = "mv.dhivehi.subtitles"
        minSdk = 24
        targetSdk = 34
        // CI builds count up so each new APK installs over the previous one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                // One password for the keystore and the key (SIGNING_PASSWORD).
                val password = System.getenv("SIGNING_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
                storeFile = file(keystorePath)
                storePassword = password
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "release"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Your own key when provided (see README), otherwise the debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    testImplementation("junit:junit:4.13.2")
}
