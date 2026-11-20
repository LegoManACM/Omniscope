plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.omniscope"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.omniscope"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Phones are 64-bit ARM; MapLibre's other native builds would add ~60 MB for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // R8 shrinks the APK from ~80 MB to something you can send as a file. Same (debug) key, so it updates in place.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    // Compress the native libraries (maps, ML Kit) inside the APK: about half the download, unpacked once at install.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The official plugin manifests (repo/plugins/official) ship inside the app, preloaded on first run.
val bundleOfficialPlugins by tasks.registering(Copy::class) {
    from(rootProject.file("../plugins/official"))
    into(layout.projectDirectory.dir("src/main/assets/plugins/official"))
    include("*/plugin.json")
}
tasks.named("preBuild") { dependsOn(bundleOfficialPlugins) }

dependencies {
    // No Google Play Services: location comes from the platform LocationManager, AR from the platform sensors.
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Camera preview behind the AR overlay.
    val camerax = "1.5.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // Vector map with OpenFreeMap tiles: free, no key, no usage limits.
    implementation("org.maplibre.gl:android-sdk:12.2.3")
    // Scan mode detectors, all on the phone. ML Kit's bundled models need no Play Services or network.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.zxing:core:3.5.3")
    // The MCP server: a one-file HTTP server.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; this is the real one.
    testImplementation("org.json:json:20240303")
}
