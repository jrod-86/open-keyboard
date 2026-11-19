plugins {
    id("com.android.application") // Android Gradle Plugin 9 builds Kotlin by itself
}

android {
    namespace = "com.example.okplugin"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.okplugin"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
