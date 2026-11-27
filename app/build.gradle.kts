plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nexa.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexa.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
