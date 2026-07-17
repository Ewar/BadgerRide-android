plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.ergpoc"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.ergpoc"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
}