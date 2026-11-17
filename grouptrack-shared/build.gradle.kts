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
    // GTIPADFW-2026-11-15 (gap C28-26319 G10): the module COMPILED for iOS but produced nothing an
    // Xcode app could link -- compiling is not producing. STATIC framework first: the smallest
    // linkable artifact, which is what proves the ports link and read real data. An XCFramework
    // comes when something is actually distributed. NO SCREENS ARE WIRED HERE, and that is SCOPE,
    // NOT A DECISION: D10 (11-06, confirmed by the spike) chose COMPOSE MULTIPLATFORM for the iPad
    // screens, and SwiftUI was REJECTED for presentation -- zero reuse of the web layer, and it
    // closes the 3.0 web door (Compose/Wasm). A stub needs no screens; D10 stands.
    // NOTE: the iOS DEPLOYMENT TARGET (16, so one binary runs on both iPad Pros) is set by the
    // consuming Xcode project, not here -- do not expect to find it in this file.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.binaries.framework {
            baseName = "GroupTrackShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // COMMKMP-2026-11-17: the Comm contract is EXPORTED, so GroupTrackShared.framework
            // carries it and Xcode links one framework rather than two.
            api(projects.grouptrackComm)
            implementation(libs.kermit)
            api(libs.okio)
            api("androidx.sqlite:sqlite:2.5.2")                  // the SQLiteConnection interface (part of this module's API)
            implementation(libs.kotlinx.serialization.json)     // org.json replacement (JsonElement API only, no plugin)
            implementation(libs.kotlinx.coroutines.core)        // STEP17G2E2A: the state data queue (coroutines in commonMain)
        }
        androidMain.dependencies { implementation("androidx.sqlite:sqlite-framework:2.5.2") }   // Android's own SQLite
        iosMain.dependencies { implementation("androidx.sqlite:sqlite-bundled:2.5.2") }         // as proved in the spike 11-06
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.kotlinx.coroutines.core) }   // STEP17G2E2F-2026-11-12: runBlocking in the 17e2 iPad test
    }
}

// GTCERT-2026-11-06: the Mac side of the certification runs on the iPad simulator ($GT_IPAD_SIM) -- CertMacTest
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    System.getenv("GT_IPAD_SIM")?.let { device.set(it) }
}
