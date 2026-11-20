/*
 * Copyright (c) 2026 GroupTrack
 */
package com.grouptrack.shared.settings

import androidx.compose.runtime.mutableStateOf
import com.grouptrack.shared.log.gtLogger

/**
 * GRPMODESHARED-2026-11-20 -- GROUP AWARENESS, one source for every platform.
 *
 * Group awareness ON = a Meshtastic radio and the group on the map; OFF = this device only, the group controls
 * greyed (not hidden), and GroupTrack makes no radio calls to the comm module. That contract is unchanged from
 * GRPMODE-2026-10-13; what moves here is WHERE the answer lives.
 *
 * It used to live in Android's `com.geeksville.mesh.convoy.GroupMode` (SharedPreferences) with the iPad holding
 * a separate `mutableStateOf(false)`, which meant the groupOnly launch modules -- RADIO SETUP among them --
 * could never appear there. Fred 11-20 07:14: group mode is a mesh function exposed through the comm module,
 * not a per-platform flag, so two sources was the defect.
 *
 * The store name and key are ANDROID'S OWN, unchanged, and GtSettingsAndroid.store(name) maps straight to
 * getSharedPreferences(name): every existing Android install reads its own saved setting through this object,
 * so nothing migrates and no device data changes.
 *
 * Telling the comm side is the CALLER's job, not this object's: ConvoyViewModel.setGroupMode already does
 * commLink.setActive(on) (:723). This holds the setting; CommLink carries it to the radio.
 */
object GtGroupMode {
    /** Android's own SharedPreferences file name -- do not rename: it is live rider data. */
    const val STORE = "grouptrack_group_mode"
    /** Android's own key -- same reason. */
    const val KEY = "group_awareness"
    const val ACTIVATE_MESSAGE =
        "Group mode needs to be activated in Settings. Do not activate unless your mesh device is available."

    /** ON = group awareness with a Meshtastic radio. Read by the screens (greying) and by the board. */
    val on = mutableStateOf(true)

    /** A greyed group control was tapped -- the popup is showing. */
    val askActivate = mutableStateOf(false)

    private var loaded = false

    /**
     * Read the stored setting once. Idempotent: later calls do nothing, so any screen may call it on entry.
     *
     * CODE RULE 1 on [defaultWhenUnset]: it is NOT a convenience default. The value genuinely differs per
     * platform for a reason that cannot be computed here -- on Android an upgrading rider's answer to the
     * first-start radio question (RadioSetupState) decides it, and that type is Android-only. The caller that
     * knows passes it; neither value is assumed on the other's behalf.
     */
    fun load(settings: GtSettings, defaultWhenUnset: Boolean) {
        if (loaded) return
        val p = settings.store(STORE)
        val stored = p.contains(KEY)
        on.value = if (stored) p.getBoolean(KEY, true) else defaultWhenUnset
        loaded = true
        gtLogger("GroupMode").i {
            "GRPMODESHARED-2026-11-20: group awareness " + (if (on.value) "ON" else "OFF") +
                (if (stored) "" else " (not set yet -- from the caller's default)")
        }
    }

    /** Persist and publish. The caller tells the comm side (CommLink.setActive); this does not. */
    fun set(settings: GtSettings, value: Boolean) {
        settings.store(STORE).edit { putBoolean(KEY, value) }
        on.value = value
        loaded = true
        gtLogger("GroupMode").i { "GRPMODESHARED-2026-11-20: group awareness set " + (if (value) "ON" else "OFF") }
    }

    /** A greyed group control was tapped: say how to turn group mode on. */
    fun askToActivate() { askActivate.value = true }
}
