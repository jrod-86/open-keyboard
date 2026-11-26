plugins {
    id("com.android.application") // Android Gradle Plugin 9 builds Kotlin by itself
}

android {
    namespace = "io.github.jrod86.okinstaller"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.jrod86.okinstaller"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
