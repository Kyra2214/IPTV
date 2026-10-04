plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.kyra.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kyra.iptv"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(libs.androidx.core.ktx)

    // Media3 / ExoPlayer (player local) e Media3 Cast (Chromecast).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.cast)

    // MediaRouteButton exige FragmentActivity e tema AppCompat (já vêm transitivamente; explícito por clareza).
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.mediarouter)

    testImplementation(libs.junit)
}
