plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Your GitHub Pages address, e.g. https://mostafatobgy.github.io/Claude_1/  (override: -PserverUrl=...)
val serverUrl: String = (project.findProperty("serverUrl") as String?) ?: "https://mostafatobgy.github.io/Claude_1/"

android {
    namespace = "com.akhbar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.akhbar.app"
        minSdk = 24
        targetSdk = 35
        // Each GitHub build gets a higher number, so a new APK installs over the old one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.$versionCode"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }
    // A fixed key (committed on purpose — it is Android's standard debug key setup, not a secret)
    // so every build is signed the same way and updates install without uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
