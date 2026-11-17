package com.geeksville.mesh.convoy

import android.content.Context
import org.json.JSONObject
import java.io.File
import com.grouptrack.shared.log.gtLogger
import com.geeksville.mesh.grouptrack.GtPathsAndroid

/**
 * ONEOPEN2-2026-11-17: THE one file-open dispatch. Moved out of ConvoyFileReceiver.onCreate so that
 * every entry point runs the SAME routing, which is Fred's rule (11-17): "click on an item launches
 * the update. clicking the file directory (Map Features) will also launch the same update."
 *
 * Entry points, all calling [receive]:
 *   1. a file tapped from OUTSIDE  -> ConvoyFileReceiver (email, Downloads, any app). bringForward = true.
 *   2. a file tapped in OUR screen -> bringForward = false; the rider is already here.
 *   3. a DIRECTORY tapped in our screen -> [receive] once per file in it.
 * ⚠ Android cannot deliver a folder tap to an app -- there is no ACTION_VIEW for a directory -- so (3)
 * exists only in our own screens. That is the reason the app-owned directories get our screens rather
 * than the system picker (Fred 11-17: "we wouldn't use the file picker for the directories owned by
 * the app. we would write our own").
 *
 * ⭐ ROUTING IS BY CONTENT, NOT BY EXTENSION, and that is deliberate: Gmail hands every attachment
 * over as application/octet-stream (GPXOPEN-2026-10-09), so an extension cannot be trusted. The
 * ORDER below is load-bearing and is 2.7e's order exactly:
 *   XML/GPX-KML first, because a ride is JSON and could never be mistaken for one;
 *   then a ride (the "grouptrack.ride" marker);
 *   then a recipe (JSON, .gtmaps slots or an AI route recipe);
 *   then a convoyDocType document;
 *   then a message naming what IS accepted -- never silent (GPXOPEN-2026-10-09).
 *
 * ⛔ THE BODIES BELOW ARE MOVED VERBATIM from the receiver. The only change is that each branch
 * RETURNS its message instead of showing a Toast and calling finish(), because two of the three entry
 * points are not an Activity. Do not "improve" the order or the tests while moving them.
 */
object GtFileOpen {

    private const val TAG = "ConvoyFileReceiver"   // 2.7e's tag, so the log reads the same

    /**
     * HEADREAD-2026-11-17: how much of a file [familyOf] needs. Routing looks at the START of the
     * content and nothing else, so a caller may read this much, ask, and read the rest ONLY if a family
     * came back.
     *
     * ⛔ WHY THIS EXISTS. ConvoyFileReceiver read the WHOLE file into a String before routing, and
     * familyOf only ever looks at the first few characters. Droid 2's real Download folder holds
     * GroupTrack_trails_EP103B_2026-11-03_1735.db at 293 MB and a debug log at 46 MB -- decoding 293 MB
     * of binary into a Kotlin String is roughly 587 MB of heap, spent to conclude "not recognised".
     * On a tablet that is an OOM, and the tap path is now the front door for everything.
     *
     * ⭐ 64 KB IS NOT A GUESS. Measured 11-17 over 477 real files: the "grouptrack.ride" tag sits at
     * offset 12 in every real ride (including the 337 KB ones), "slots" at 45 or less across 73 real
     * recipes, "<gpx" at 39 or less across 396 real tracks. looksLikeGpxOrKml reads 4000 of its own.
     * ⚠ THE LIMIT IS A CONTRACT, NOT AN OPTIMISATION: a format that buried its marker past 64 KB would
     * not be recognised. That is deliberate and documented, where an unbounded read is an OOM nobody
     * documented.
     */
    const val HEAD_CHARS = 64 * 1024

    /**
     * FILEFAMILY-2026-11-17 (Fred): "if we want to move an imported item to the proper directory post
     * history as a backup." These are the four app-owned directories, and the NAMES are Fred's.
     *
     * ⭐ THE DISPATCH IS THE ONLY PLACE THAT CAN SAY. [receive] decides the type by reading the
     * CONTENT -- extensions cannot be trusted, since Gmail sends everything as octet-stream. So the
     * family falls out of the branch that already handled the file. Deriving it a second time
     * elsewhere would mean two content sniffers that must agree forever, which is the drift this whole
     * day has been about.
     */
    enum class Family(val dirName: String) {
        MAP_FEATURES("GroupTrack Map Features"),   // routes, tracks, waypoints, rider trails
        RIDES("GroupTrack Rides"),                 // ride packages, ride stats
        MAPS("GroupTrack Maps"),                   // map recipes, route recipes

        /**
         * FOURTHDIR-2026-11-17: THE FOURTH DR DIRECTORY, restored. It was removed on a misreading --
         * "three folders maps, features rides" was Fred REPORTING WHAT APPEARED on the device, not
         * asking for three. The fourth was missing because of the bug fixed in GtFileHome's lookup
         * (a prefix LIKE matched the sibling folders), not because it was unwanted.
         *
         * Holds what the other three do not: convoy documents (convoy_ride / convoy_map_region /
         * convoy_route / convoy_master), and anything else that is part of the recovery position
         * without being a map, a feature or a ride.
         *
         * ⭐ WHY A MEMBER WITH NO DIRECTORY, instead of deleting the fourth one. Dropping it outright
         * would make familyOf return null for a convoy document -- and null already means NOTHING
         * RECOGNISED IT, which [receive] answers with "This file isn't a GroupTrack OG ride, map
         * recipe, route recipe or a GPX / KML track." A convoy_ride / convoy_map_region / convoy_route
         * / convoy_master document IS recognised and IS imported; it simply has no rider-facing folder.
         * Those are two different questions, and the four-folder design was letting one value answer
         * both. [dirName] null = recognised, imported, not archived.
         * ⚠ Nothing else belongs here by default. A backup set, a config PDF and an issue report are
         * app documents rather than rider data -- they are emailed, and the DR position they would have
         * duplicated now lives in the three folders themselves.
         */
        ROOT("GroupTrack"),
    }

    /**
     * What one file's open produced: the rider's [message] ("" = say nothing) and the [family] the
     * item belongs to, or null when nothing recognised it and there is nothing to file.
     *
     * ⚠ FILING IS THE CALLER'S JOB, NOT THIS OBJECT'S, and that is deliberate rather than lazy: the
     * destination root does not exist yet. It is the granted tree under Downloads, which arrives with
     * the install-flow grant. Writing a destination here now would mean inventing a root and then
     * changing it -- so this reports the family and stops, and the caller files when there is somewhere
     * to file to. The decision is captured today; the write waits for the root.
     */
    class Outcome(val message: String, val family: Family?)

    /**
     * FAMILYOF-2026-11-17: WHICH directory this content belongs to, with NO side effect -- the routing
     * decision on its own. [receive] switches on this rather than re-testing, so the rule exists once,
     * and the Droid cert can verify the routing without staging a GPX or importing a ride.
     *
     * ⚠ THE ORDER IS THE RULE and it is 2.7e's: XML/GPX-KML, then a ride, then a recipe, then a convoy
     * document. A JSON file that is not a recipe but carries convoyDocType still reaches ROOT, because
     * the recipe test fails before the document test is made -- the same fall-through the inline
     * dispatch had.
     * ⚠ null means NOTHING RECOGNISED IT, which is not the same as "it failed": a recognised file whose
     * import fails still has a family, and is still archived, because a copy of what arrived is what a
     * recovery wants.
     */
    fun familyOf(content: String): Family? {
        if (content.trimStart().startsWith("<") && GpxOpen.looksLikeGpxOrKml(content)) return Family.MAP_FEATURES
        if (content.contains("\"grouptrack.ride\"")) return Family.RIDES
        if (content.trimStart().startsWith("{")) {
            val o = try { JSONObject(content) } catch (e: Exception) { null }
            if (o != null && com.geeksville.mesh.grouptrack.GtRecipes.kind(o) != null) return Family.MAPS
        }
        if (content.contains("convoyDocType")) return Family.ROOT
        return null
    }

    /**
     * Routes one file's [content] and returns the message for the rider. "" = nothing to say (the
     * caller shows nothing); 2.7e logged and fell through silently in exactly one case, kept below.
     */
    // NULLNAME-2026-11-17: [name] is String? because GpxOpen.displayName is -- "null = the sender gave
    // none" (CODE RULE 1), and both handlers this passes it to take it nullable. Declaring it non-null
    // here only moved the problem to the caller.
    fun receive(ctx: Context, name: String?, content: String, bringForward: Boolean): Outcome {
        // FAMILYOF-2026-11-17: ONE routing decision, then the action for it. The branch comments below
        // stay with their actions; the tests they used to carry live in [familyOf].
        when (familyOf(content)) {
            // GPXOPEN-2026-10-09: an XML file that is GPX or KML goes to THE one GPX import -- staged
            // and opened in the import panel (tracks, routes and waypoints alike), like a ride's GPX.
            Family.MAP_FEATURES ->
                return Outcome(GpxOpen.stageAndOffer(ctx, name, content, bringForward = bringForward), Family.MAP_FEATURES)

            // RIDEIMPORT2-2026-09-24 / RIDEIMPORT3-2026-09-25: a GroupTrack ride file (format 3) --
            // stored unchanged as rides/<rideId>.json, then its GPX staged and the import panel opened
            // with it ticked.
            Family.RIDES ->
                return Outcome(RideImport.importRide(ctx, content, bringForward = bringForward), Family.RIDES)

            // MAPRECIPES-2026-11-03: a map recipe (.gtmaps -- "slots") or an AI route recipe
            // (anchorLat + milesHigh).
            Family.MAPS -> {
                val o = try { JSONObject(content) } catch (e: Exception) { null }
                // ⚠ familyOf already parsed and recognised it, so o is non-null here and the message is
                // non-empty. The fallback is kept rather than asserted: an unreachable branch that
                // throws is worse than one that falls through to the message below.
                if (o != null) {
                    val m = com.geeksville.mesh.grouptrack.GtRecipes.receive(ctx, name, content, o)
                    if (m.isNotEmpty()) return Outcome(m, Family.MAPS)
                }
            }

            Family.ROOT -> { }   // falls through to the convoyDocType handling below, as before

            null -> { }          // falls through to the "not a convoy file" message below
        }

        // Validate this is actually a convoy file before processing
        if (!content.contains("convoyDocType")) {
            gtLogger(TAG).w { "Not a convoy file — ignoring" }
            // GPXOPEN-2026-10-09: never silent -- the rider is told what happened.
            // nothing recognised it: no family, so there is nothing to file as history
            return Outcome("This file isn't a GroupTrack OG ride, map recipe, route recipe or a GPX / KML track.", null)
        }

        // Parse JSON and read convoyDocType
        val json = try {
            JSONObject(content)
        } catch (e: Exception) {
            gtLogger(TAG).e { "Not valid JSON — ignoring file: ${e.message}" }
            return Outcome("", null)   // ⚠ 2.7e said NOTHING here (log only, no Toast). Kept.
        }

        val docType = json.optString("convoyDocType", "unknown")
        gtLogger(TAG).i { "convoy file received — docType=$docType" }

        // Route to correct import directory based on docType
        val importDirName = when (docType) {
            "convoy_ride"        -> "convoy_import"
            "convoy_map_region"  -> "convoy_map_import"
            "convoy_route"       -> "convoy_route_import"
            "convoy_master"      -> "convoy_master_import"
            else -> {
                gtLogger(TAG).w { "Unknown convoyDocType: $docType — discarding" }
                return Outcome("", null)   // ⚠ 2.7e said nothing here either. Kept.
            }
        }

        // Write to import directory
        val importDir = File(GtPathsAndroid.files(ctx), importDirName).also { it.mkdirs() }
        val fileName = "convoy_${System.currentTimeMillis()}.convoy"
        val destFile = File(importDir, fileName)
        destFile.writeText(content)

        gtLogger(TAG).i { "Saved $docType to $importDirName/$fileName" }
        gtLogger(TAG).i { "Convoy file saved successfully — $docType ready for import" }
        return Outcome("", Family.ROOT)   // 2.7e showed nothing: the rider sees it next time the convoy menu opens.
    }

    /**
     * ONEOPEN2-2026-11-17: the DIRECTORY entry point -- Fred's rule, one handler. Every file in [dir],
     * each through [receive], in NAME ORDER so a run is repeatable.
     *
     * ⚠ ORDER IS BY NAME AND NOT BY TYPE. It would be tempting to import recipes before GPX so maps
     * exist first, but the DR position's own recovery steps already tell the rider that order and
     * "importing something that is already there is safe -- it is not added twice" (Backup.kt). A
     * hidden reordering here would be a second, invisible policy.
     * ⚠ ONE FAILURE DOES NOT STOP THE RUN. A single unreadable file in a recovery folder must not
     * abandon the rest of the recovery; each file's outcome is collected and reported together.
     */
    fun receiveDirectory(ctx: Context, dir: File, bringForward: Boolean): List<Pair<String, String>> {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        gtLogger(TAG).i { "ONEOPEN2-2026-11-17: ${dir.name} -- ${files.size} file(s)" }
        return files.map { f ->
            val outcome = try {
                receive(ctx, f.name, f.readText(), bringForward).message.ifEmpty { "done" }
            } catch (e: Exception) {
                gtLogger(TAG).e { "ONEOPEN2-2026-11-17: ${f.name} failed: ${e.message}" }
                "could not be read: ${e.message}"
            }
            f.name to outcome
        }
    }
}
