plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nabd.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nabd.tv"
        minSdk = 21
        targetSdk = 35
        // CI run number keeps versionCode increasing, so each build installs over the previous one.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "2.0.$run"
    }
    // One fixed key for every build (CI would otherwise generate a new debug key each run,
    // and Android refuses to update an app signed with a different key).
    signingConfigs {
        create("nabd") {
            storeFile = file("nabd-release.jks")
            storePassword = System.getenv("KEYSTORE_PASS")?.takeIf { it.isNotBlank() } ?: "nabdtv-sign"
            keyAlias = "nabd"
            keyPassword = System.getenv("KEYSTORE_PASS")?.takeIf { it.isNotBlank() } ?: "nabdtv-sign"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("nabd") }
        release {
            signingConfig = signingConfigs.getByName("nabd")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
