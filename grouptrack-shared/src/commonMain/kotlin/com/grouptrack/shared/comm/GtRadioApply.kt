package com.grouptrack.shared.comm

import com.grouptrack.comm.ApplyRequest
import com.grouptrack.comm.CommConfig
import com.grouptrack.comm.CommResult
import com.grouptrack.shared.ride.RideFiles

/**
 * RADIOAPPLY-2026-11-21: the RADIO WRITE service, shared.
 *
 * Fred 11-21 14:49/14:54: one service that takes the radio config's FILE NAME, reads the new config
 * values out of it, and calls the radio write with them. And 14:46, which is why it is a service rather
 * than a line inside a screen: "radio setup is a set of functions[;] one of those functions is the same
 * apply radio config required in [the] chain." The same write serves two chains:
 *
 *   N - RADIO SETUP    RadioConfigScreen.kt:177 -- the rider picks a target and applies it
 *   B1 - CHECK IN      the same screen entered with autoRideId (CHECKINAPPLY-2026-09-28): that ride,
 *                      no picking and no preview -- apply, then report back
 *
 * ⛔ WHAT THIS SERVICE DOES NOT DO, deliberately: it does not read or save the radio's CURRENT
 * configuration first. The comm side owns that -- "WHAT goes on the radio; the comm side does the HOW
 * (first save, writer, verify, save) and reports" (COMMCONFIG-2026-10-12). Two components both owning
 * the backup is worse than one.
 *
 * TRANSCRIBED from Android's RadioConfigScreen.kt:118-181. Reading the file uses the existing
 * [RideFiles] seam, which both platforms already implement -- so the file half works on the iPad today
 * even though the write does not.
 *
 * ON THE iPAD TODAY: `GtIosCommConfig` returns [CommResult.NotSupported] for both writes, so [write]
 * returns NotSupported. That is the honest reading for a platform with no radio yet
 * (GRPIOS-2026-11-19), not a stub reporting success. When the iPad has a radio, those two overrides are
 * the only change required -- everything here already does its half.
 */
object GtRadioApply {

    /** Android's own reason string for "there is no radio connected" (RadioConfigScreen.kt:181). */
    const val NO_RADIO: String = "NO_RADIO"

    /** The bundled GroupTrack default configuration (RadioConfigScreen.kt:118). */
    const val DEFAULT_ASSET: String = "grouptrack_default.json"

    /**
     * The config values for a ride file, by FILE NAME inside the rides folder -- Fred's input.
     * CODE RULE 1: null = no such file, which is Android's own `runCatching { ... }.getOrNull()` at
     * RadioConfigScreen.kt:127. Checked with exists/isFile first because [RideFiles.readText] throws.
     */
    fun configDoc(fs: RideFiles, fileName: String): String? {
        val path = fs.child(fs.ridesDir(), fileName)
        if (!fs.exists(path) || !fs.isFile(path)) return null
        return fs.readText(path)
    }

    /**
     * The GroupTrack default's config values, from the bundled asset. Android reads this through its
     * assets manager; iOS reads the same name out of the app bundle. Throws if the asset is missing,
     * which is deliberate -- a missing bundled default is a build fault, not a runtime condition.
     */
    fun defaultConfigDoc(fs: RideFiles): String = fs.readAsset(DEFAULT_ASSET)

    /**
     * What to write. Android holds these as one target type whose `json` and `savedId` are each
     * nullable and writes `savedId!!` because exactly one is always set; expressing that as a sealed
     * type makes the invariant structural, so there is no impossible third case to invent a result for.
     */
    sealed interface Target {
        /** The title the comm side saves the applied configuration under. */
        val saveTitle: String

        /**
         * A configuration document to write -- a ride's network, or the GroupTrack default.
         * CODE RULE 1: [rideId] null is a real value, meaning this target is not a ride.
         */
        data class Ride(
            val configDoc: String,
            val callsign: String,
            val title: String,
            override val saveTitle: String,
            val rideId: String?,
        ) : Target

        /** A configuration already saved on this device, by its id (SAVEDCFG-2026-10-12). */
        data class Saved(
            val savedId: String,
            override val saveTitle: String,
        ) : Target
    }

    /**
     * Build the write target from a ride FILE NAME -- piece 2 and piece 3 joined, which is the whole
     * point of the service. Returns null when the file is not there, so a caller never writes a
     * half-read configuration.
     */
    fun targetFromFile(
        fs: RideFiles,
        fileName: String,
        callsign: String,
        title: String,
        saveTitle: String,
        rideId: String?,
    ): Target.Ride? {
        val doc = configDoc(fs, fileName) ?: return null
        return Target.Ride(doc, callsign, title, saveTitle, rideId)
    }

    /**
     * Call the radio write for [target]. [ApplyRequest]'s argument order is Android's own --
     * configDoc, callsign, title, saveTitle, rideId -- and four of the five are Strings, so it is kept
     * exactly rather than rebuilt.
     */
    suspend fun write(config: CommConfig, target: Target): CommResult = when (target) {
        is Target.Ride -> config.applyRide(
            ApplyRequest(target.configDoc, target.callsign, target.title, target.saveTitle, target.rideId),
        )
        is Target.Saved -> config.applySaved(target.savedId, target.saveTitle)
    }

    /**
     * The only success that counts: the radio took the write AND the comm side read it back.
     * [CommResult.Applied] with `verified` false means written but NOT confirmed, which Android does
     * not treat as done (RADIOSETUPTEST-2026-10-01).
     */
    fun verified(result: CommResult): Boolean =
        result is CommResult.Applied && result.verified

    /** The failure both callers message on by name rather than showing the raw reason. */
    fun noRadio(result: CommResult): Boolean =
        result is CommResult.Failed && result.reason == NO_RADIO
}
