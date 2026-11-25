plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nabd.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jolo.tv"   // namespace stays com.nabd.tv (source packages); only the installed app id changes
        minSdk = 24
        targetSdk = 35
        // CI run number keeps versionCode increasing, so each build installs over the previous one.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "2.0.$run"
        // The TMDB key lives only in GitHub Secrets (TMDB_API_KEY), baked in at build time.
        // Nothing secret is stored in the repo, and there is no Settings field for it.
        buildConfigField("String", "TMDB_API_KEY", "\"${System.getenv("TMDB_API_KEY") ?: ""}\"")
    }
    // One fixed key for every build (CI would otherwise generate a new debug key each run,
    // and Android refuses to update an app signed with a different key).
    // The signing key lives only in GitHub Secrets (KEYSTORE_B64 + KEYSTORE_PASS); nothing secret is stored in the repo.
    // Without them (e.g. a local build) the APK falls back to the standard Android debug key.
    val ksPass: String? = System.getenv("KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
    val ksFile = file("nabd-release.jks")
    val canSign = ksPass != null && ksFile.exists()
    signingConfigs {
        if (canSign) {
            create("nabd") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = System.getenv("KEYSTORE_ALIAS")?.takeIf { it.isNotBlank() } ?: "jolo"
                keyPassword = ksPass
            }
        }
    }
    buildTypes {
        debug { if (canSign) signingConfig = signingConfigs.getByName("nabd") }
        release {
            if (canSign) signingConfig = signingConfigs.getByName("nabd")
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
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    // 1.9.x is the newest line that still compiles with compileSdk 35 / AGP 8.7 (1.10+ is built against compileSdk 36).
    val media3 = "1.9.4"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.tvprovider:tvprovider:1.0.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
