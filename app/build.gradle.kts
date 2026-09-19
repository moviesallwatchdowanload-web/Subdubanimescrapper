plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.megix"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

cloudstream {
    language = "hi"
    description = "SubDubAnime CloudStream Extension"
    authors = listOf("megix")
    status = 1
    tvTypes = listOf(
        "Anime",
        "TvSeries",
        "Movie"
    )
}

dependencies {
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
