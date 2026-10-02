plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.metersnap.offline"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.metersnap.offline"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-field-sweep"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    // Bundled Latin OCR model: available immediately and works without network access.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
