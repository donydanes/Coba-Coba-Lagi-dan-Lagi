plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.framegen"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.framegen.fg"
        minSdk = 34
        targetSdk = 35
        versionCode = 11
        versionName = "8.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
