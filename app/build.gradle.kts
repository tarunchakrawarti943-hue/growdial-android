plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- change these two lines for your app ----
val siteUrl = "https://growdial.com/"          // the website the app opens
val appVersion = 1                              // +1 every time you upload a new version to Play Store

android {
    namespace = "com.growdial.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.growdial.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion
        versionName = "1.0.$appVersion"
        buildConfigField("String", "SITE_URL", "\"$siteUrl\"")
    }

    // Release signing: the GitHub workflow fills these from the repository secrets (see README).
    val ks = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ks != null && file(ks).exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
}
