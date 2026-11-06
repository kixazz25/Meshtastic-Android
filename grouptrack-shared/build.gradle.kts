// :grouptrack-shared -- GTSHARED-2026-11-06 (release 2.8, plan v3 sections 9-10): GroupTrack's MODEL layer, shared by the Android app
// and the iPad app. The FIRST module in this repo with iOS targets. Swaps used by the model: Kermit (logging), Okio (files),
// kotlinx JSON / datetime, androidx.sqlite (SQLite -- bundled, the same SQLite on both platforms).
plugins {
    alias(libs.plugins.meshtastic.kmp.library)
}

kotlin {
    @Suppress("UnstableApiUsage")
    android {
        namespace = "com.grouptrack.shared"
        androidResources.enable = false
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kermit)
            api(libs.okio)
            implementation("androidx.sqlite:sqlite-bundled:2.5.2")   // as proved in the spike 11-06
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
