// Voice input module, adapted from Whisper+ (https://github.com/woheller69/whisperIMEplus, GPL-3.0)
plugins {
    id("com.android.library")
}

android {
    namespace = "com.whisperonnx"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release { isMinifyEnabled = false }
        create("nouserlib") { isMinifyEnabled = false }
        create("runTests") { isMinifyEnabled = false }
        create("debugNoMinify") { isMinifyEnabled = false }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    lint {
        disable += "MissingTranslation"
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.preference:preference:1.2.1")

    implementation("com.google.guava:guava:31.1-android")
    implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")
    implementation("com.github.houbb:opencc4j:1.14.0")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.12.4")
}
