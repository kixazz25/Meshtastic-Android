// :grouptrack-shared -- GTSHARED-2026-11-06 (release 2.8, plan v3 sections 9-10): GroupTrack's MODEL layer, shared by the Android app
// and the iPad app. The FIRST module in this repo with iOS targets. Swaps used by the model: Kermit (logging), Okio (files),
// kotlinx JSON, androidx.sqlite (SQLite).
// GTBATCH1A-2026-11-06: ONE SQLITE PER PLATFORM -- Android = the system SQLite the app already uses (sqlite-framework), so the shared code
// is just another connection on the same engine; iPad = the bundled SQLite. Two SQLite copies on one file in one app can corrupt it.
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
            api("androidx.sqlite:sqlite:2.5.2")                  // the SQLiteConnection interface (part of this module's API)
            implementation(libs.kotlinx.serialization.json)     // org.json replacement (JsonElement API only, no plugin)
        }
        androidMain.dependencies { implementation("androidx.sqlite:sqlite-framework:2.5.2") }   // Android's own SQLite
        iosMain.dependencies { implementation("androidx.sqlite:sqlite-bundled:2.5.2") }         // as proved in the spike 11-06
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// GTCERT-2026-11-06: the Mac side of the certification runs on the iPad simulator ($GT_IPAD_SIM) -- CertMacTest
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    System.getenv("GT_IPAD_SIM")?.let { device.set(it) }
}
