// :grouptrack-shared -- GTSHARED-2026-11-06 (release 2.8, plan v3 sections 9-10): GroupTrack's MODEL layer, shared by the Android app
// and the iPad app. The FIRST module in this repo with iOS targets. Swaps used by the model: Kermit (logging), Okio (files),
// kotlinx JSON, androidx.sqlite (SQLite).
// GTBATCH1A-2026-11-06: ONE SQLITE PER PLATFORM -- Android = the system SQLite the app already uses (sqlite-framework), so the shared code
// is just another connection on the same engine; iPad = the bundled SQLite. Two SQLite copies on one file in one app can corrupt it.
plugins {
    alias(libs.plugins.meshtastic.kmp.library)
    // CMPPIPE-2026-11-18: Compose Multiplatform for the iPad SCREENS. D10 (11-06) chose CMP and
    // REJECTED SwiftUI for presentation, so this is the sanctioned path, not a new decision.
    // The convention plugin applies compose-compiler + org.jetbrains.compose and puts the CMP
    // RUNTIME and RESOURCES in commonMain -- it does NOT bring the UI toolkit, so compose.ui /
    // foundation / material3 are added explicitly below.
    alias(libs.plugins.meshtastic.kmp.library.compose)
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
            // CMPPIPE-2026-11-18: the UI toolkit itself. The kmp.library.compose convention gives
            // only runtime + resources. compose.* accessors come from the org.jetbrains.compose
            // plugin that convention applies.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // IOSFIX-2026-11-19: Icons.Filled.{Key,FilterAlt,Adjust,Navigation} in GroupTrack.kt's
            // MENU and map bar. Those four are NOT in the ~40-icon core set, so the extended
            // artifact is required; Kotlin/Native strips every icon the binary does not use.
            implementation(compose.materialIconsExtended)
        }
        androidMain.dependencies { implementation("androidx.sqlite:sqlite-framework:2.5.2") }   // Android's own SQLite
        iosMain.dependencies {
            implementation("androidx.sqlite:sqlite-bundled:2.5.2")                                 // as proved in the spike 11-06
            // IOSSDK-2026-11-20 (Path B): the official Meshtastic KMP SDK is the iPad's mesh layer. PINNED --
            // it is pre-1.0 and its own README warns APIs may change between minor versions. 0.2.0 = 2026-10-03.
            implementation("org.meshtastic:sdk-core:0.2.0")
            implementation("org.meshtastic:sdk-transport-ble:0.2.0")
            // IOSSDK-2026-11-20 v2: Kable is declared HERE because transport-ble 0.2.0 declares it
            // implementation(), not api(), while BleTransport's public constructor takes a Kable Peripheral.
            // PINNED to the version the SDK was built against -- its own catalog says the klib ABI must match.
            implementation("com.juul.kable:kable-core:0.45.0")
            // IOSCOMM-2026-11-20: RadioClient.Builder requires storage(); baseDir = "" is the SDK's own
            // in-memory mode, so this adds no files and no paths until persistence is wired.
            implementation("org.meshtastic:sdk-storage-sqldelight:0.2.0")
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.kotlinx.coroutines.core) }   // STEP17G2E2F-2026-11-12: runBlocking in the 17e2 iPad test
    }
}

// GTCERT-2026-11-06: the Mac side of the certification runs on the iPad simulator ($GT_IPAD_SIM) -- CertMacTest
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    System.getenv("GT_IPAD_SIM")?.let { device.set(it) }
}
