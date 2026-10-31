// Stand-in for the external "assrender" module (ASS/SSA subtitle renderer).
// The original lives outside this repository (../assrender/assrender), so a fresh clone cannot build.
// This stub exposes the same classes the app uses, but does no ASS rendering:
// ASS/SSA subtitles fall back to ExoPlayer's standard (basic) subtitle handling.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.assrender"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    // compileOnly: the app already provides Media3 at runtime (playbackcore + local ExoPlayer AAR).
    val media3Version = "1.10.0-beta01"
    compileOnly("androidx.media3:media3-common:$media3Version")
    compileOnly("androidx.media3:media3-extractor:$media3Version")
    compileOnly("androidx.media3:media3-exoplayer:$media3Version")
}
