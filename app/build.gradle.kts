plugins {
    id("com.android.application")
}

android {
    namespace = "com.badgerride"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.badgerride"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    // Health Connect (finished rides are exported as workouts) + the coroutines
    // its client API is built on - the only async framework in the app.
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
