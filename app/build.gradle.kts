plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest")) {
        exclude("**/PlaylistRepositoryTest.kt")
    }
}

android {
    namespace = "com.kyra.iptv"
    compileSdk = 35

    val stableKeystore = rootProject.file("iptv-stable-debug.keystore")
    if (stableKeystore.exists()) {
        signingConfigs {
            create("stableDebug") {
                storeFile = stableKeystore
                storePassword = System.getenv("IPTV_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("IPTV_KEY_ALIAS") ?: "iptv-stable-debug"
                keyPassword = System.getenv("IPTV_KEY_PASSWORD") ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "com.kyra.iptv"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
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

    testOptions {
        unitTests.all {
            it.jvmArgs("--add-modules=jdk.httpserver")
        }
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
