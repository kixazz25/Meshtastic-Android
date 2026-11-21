/*
 * Copyright (c) 2026 GroupTrack
 */
package com.grouptrack.shared.settings

import androidx.compose.runtime.mutableStateOf
import com.grouptrack.shared.log.gtLogger

/**
 * ISSUEMODE-2026-11-20 -- WHERE AN ISSUE REPORT GOES, one source for every platform.
 *
 * ON = ISSUE emails the report; OFF = ISSUE saves it on this device. That contract is
 * ISSUE-2026-10-24's and is unchanged; what moves here is WHERE the answer lives. The iPad held it
 * in a composable's `remember`, so the rider's choice survived until the composition ended and then
 * silently returned to ON.
 *
 * ⚠ THE STORED FORM IS A STRING, NOT A BOOLEAN, and that is Android's, not a choice made here.
 * GtIssue.kt:68-74 writes "email" or "device" under key "send_to" in the "gt_issue" file, and reads
 * it back as `getString(KEY, "email") != "device"`. So:
 *   - absent  -> email (the default is in the READ, not in a stored value)
 *   - "device" -> device
 *   - anything else, including a value some future version writes -> email
 * Reproduced exactly. A boolean here would have been a second, incompatible format for one rider
 * setting, and the two would disagree the first time a rider moved between platforms.
 *
 * The store name and key are ANDROID'S OWN and must not be renamed: they are live rider data, and
 * GtSettingsAndroid.store(name) maps straight to getSharedPreferences(name), so every existing
 * install reads its own saved answer through this object with nothing to migrate.
 *
 * Sending is the CALLER's job, not this object's. This holds the setting; GtArtifactSink.email /
 * emailAll carry a report out when ISSUE actually fires.
 */
object GtIssueMode {

    /** Android's own SharedPreferences file name (GtIssue.kt:68) -- do not rename: live rider data. */
    const val STORE = "gt_issue"

    /** Android's own key (GtIssue.kt:69) -- same reason. */
    const val KEY = "send_to"

    /** Android's own values (GtIssue.kt:73). */
    const val EMAIL = "email"
    const val DEVICE = "device"

    /** true = ISSUE emails the report; false = it is saved on this device. Read by the settings row. */
    val sendsEmail = mutableStateOf(true)

    private var loaded = false

    /** Read the stored setting once. Idempotent, so any screen may call it on entry. */
    fun load(settings: GtSettings) {
        if (loaded) return
        // ⚠ Android's own expression, not a reinterpretation: NOT "device" means email.
        val stored = settings.store(STORE).getString(KEY, EMAIL)
        sendsEmail.value = stored != DEVICE
        loaded = true
        gtLogger("GtIssueMode").i {
            "ISSUEMODE-2026-11-20: issue reports go to " + (if (sendsEmail.value) "EMAIL" else "THIS DEVICE") +
                " (stored: " + (stored ?: "unset") + ")"
        }
    }

    /** Persist and publish, in Android's string form. */
    fun set(settings: GtSettings, email: Boolean) {
        settings.store(STORE).edit { putString(KEY, if (email) EMAIL else DEVICE) }
        sendsEmail.value = email
        loaded = true
        gtLogger("GtIssueMode").i {
            "ISSUEMODE-2026-11-20: issue reports set to " + (if (email) "EMAIL" else "THIS DEVICE")
        }
    }
}
