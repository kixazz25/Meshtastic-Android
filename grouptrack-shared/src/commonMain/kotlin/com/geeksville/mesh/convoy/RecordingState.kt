package com.geeksville.mesh.convoy

// ONEMAPHOST-2026-10-25: moved here from ConvoyScreen.kt when ONE MAP became GroupTrackScreen -- the convoy services use them.
// IOSREC-2026-11-19: moved again, app -> commonMain, so the iPad's record control is the SAME state machine.
// The package name does not change, so all three consumers (GroupTrackScreen, ConvoyViewModel, RideStats)
// compile untouched -- no facade and no typealiases. Pure Kotlin: nothing here was platform-bound.

/** The recording state of the ride (REC / CHK IN / PAUSE ...). */
enum class RecordingState { CHECK_IN, IDLE, RECORDING, PAUSED, SLEEPING }   // CHECKIN-2026-09-27: CHECK_IN first

// PRESETPANEL-2026-10-20 (Fred 10-20): the presets the GPS panel offers, by Meshtastic name, and their words.
object GpsPresets {
    val choices = listOf("MEDIUM_FAST" to "Medium Fast", "LONG_FAST" to "Long Fast")
    // CODE RULE 1: null = not read yet (or custom settings) -> "preset --"; a preset not offered here shows its own name.
    fun label(p: String?): String = if (p == null) "preset --" else choices.firstOrNull { it.first == p }?.second ?: p
}
