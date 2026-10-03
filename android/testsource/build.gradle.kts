plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fake "music app" for automated Engine A/B tests on an emulator or device.
// Flavor `capturable` allows playback capture (like most players);
// flavor `blocked` opts out at app level (like Spotify).
android {
    namespace = "dev.equalizer.testsource"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.equalizer.testsource"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    flavorDimensions += "capture"
    productFlavors {
        create("capturable") { dimension = "capture"; applicationIdSuffix = ".capturable" }
        create("blocked") { dimension = "capture"; applicationIdSuffix = ".blocked" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
