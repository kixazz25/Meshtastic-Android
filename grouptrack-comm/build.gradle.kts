// :grouptrack-comm — the GroupTrack Comm API (contract only). COMMAPI3-2026-10-08, 2.7b cycle 1 step 1.
// Plain Kotlin types + interfaces. Depends on NOTHING transport-specific: no Meshtastic, no screens (design §R6.1).
import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.meshtastic.android.library)
}

configure<LibraryExtension> { namespace = "com.grouptrack.comm" }

dependencies { api(libs.kotlinx.coroutines.core) }   // api: Flow/StateFlow are part of the contract
