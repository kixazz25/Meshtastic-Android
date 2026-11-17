// :grouptrack-comm — the GroupTrack Comm API (contract only). COMMAPI3-2026-10-08, 2.7b cycle 1 step 1.
// Plain Kotlin types + interfaces. Depends on NOTHING transport-specific: no Meshtastic, no screens (design §R6.1).
// COMMKMP-2026-11-17: KMP, so the iPad can link the contract. The FILE needed nothing -- 365 lines, two
// imports (Flow, StateFlow), no android/androidx/java reference anywhere. Only the module was Android-only.
// ⚠ The iOS binary is NOT declared here: grouptrack-shared exports this module with api(), so the
// contract travels inside GroupTrackShared.framework and Xcode links ONE framework, not two.
plugins {
    alias(libs.plugins.meshtastic.kmp.library)
}

kotlin {
    @Suppress("UnstableApiUsage")
    android {
        namespace = "com.grouptrack.comm"
        androidResources.enable = false
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { /* no framework here -- see the note above */ }

    sourceSets {
        // api: Flow/StateFlow are part of the contract, as the 10-08 module note said
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
    }
}
