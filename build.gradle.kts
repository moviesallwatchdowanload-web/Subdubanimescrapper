plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
}

android {
    compileSdk 33

    defaultConfig {
        minSdk 21
        targetSdk 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // CloudStream dependency (adjust versioning as per standard repo setup)
    implementation("com.github.lagradost:cloudstream3:3.7.4")
}
