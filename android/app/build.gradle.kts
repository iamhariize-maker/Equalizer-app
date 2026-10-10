plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val productionBuild = providers.gradleProperty("svanProduction").orNull == "true"
val signingInputs = listOf("SVAN_KEYSTORE", "SVAN_STORE_PASSWORD", "SVAN_KEY_ALIAS", "SVAN_KEY_PASSWORD")
val productionSigning = signingInputs.associateWith { providers.environmentVariable(it).orNull }
if (productionBuild) {
    require(productionSigning.values.all { !it.isNullOrBlank() }) {
        "Production signing requires SVAN_KEYSTORE, SVAN_STORE_PASSWORD, SVAN_KEY_ALIAS and SVAN_KEY_PASSWORD"
    }
}

android {
    namespace = "app.svan"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "app.svan"
        // 29: AudioPlaybackCapture (capture engine). DynamicsProcessing needs 28.
        minSdk = 29
        targetSdk = 36
        versionCode = 21
        versionName = if (productionBuild) "0.5.14" else "0.5.14-audiophile-preview"
        buildConfigField("boolean", "PHONE_PREVIEW", (!productionBuild).toString())
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") }
        }
    }

    if (productionBuild) {
        signingConfigs.create("production") {
            storeFile = file(productionSigning.getValue("SVAN_KEYSTORE")!!)
            storePassword = productionSigning.getValue("SVAN_STORE_PASSWORD")
            keyAlias = productionSigning.getValue("SVAN_KEY_ALIAS")
            keyPassword = productionSigning.getValue("SVAN_KEY_PASSWORD")
        }
    }

    // Preview builds (CI artifact, sideloaded testers) share ONE fixed key so each new APK updates the
    // previous one in place. This key is public on purpose and is NOT the Play release key.
    signingConfigs.create("preview") {
        storeFile = rootProject.file("preview.keystore")
        storePassword = "svanpreview"
        keyAlias = "svanpreview"
        keyPassword = "svanpreview"
    }

    buildTypes {
        release {
            // R8 shrinks Compose and icons from ~57 MB (debug) to a phone-friendly size.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Preview builds use the fixed preview key (stable across CI runs, so updates install over each other).
            signingConfig = signingConfigs.getByName(if (productionBuild) "production" else "preview")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" } // matches Kotlin 1.9.24
    testOptions { unitTests.isReturnDefaultValues = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
