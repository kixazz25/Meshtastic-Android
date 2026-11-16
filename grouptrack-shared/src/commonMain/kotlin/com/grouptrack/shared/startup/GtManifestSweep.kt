package com.grouptrack.shared.startup

import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.osm.GtOsmLedger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path

/**
 * SWEEPPORT-2026-11-16: HomeStateImportController.sweepManifests, ported AS IS.
 *
 * WHAT IT IS, in the owner's words (Fred 2026-11-16 15:57): "it is the import json for trails. did it
 * complete properly do it need to be rerun." It is the INTERRUPTED-IMPORT DETECTOR for trail data, not
 * housekeeping clutter -- which is why it is ported rather than left as a hole on iOS.
 *
 * THE FOUR BEHAVIOURS THAT ARE THE POINT, each carrying its original marker:
 *  - MARKERFIX-2026-08-25M: A MANIFEST HAS A `sources` ARRAY. Filtering on the extension alone is what
 *    swept clearTrailsOnce's own marker into history/ and stamped it "killed" -- a file this routine had
 *    no business touching. The sources test is the durable half of that fix.
 *  - STAMP FIRST, THEN MOVE. process_state = "killed" + killed_at are written to the file in place
 *    before it is moved, so an interrupted move leaves a correctly stamped manifest.
 *  - SWEEPNAME-2026-08-21I: SANITIZE THE FILENAME. manifest_id carries an ISO timestamp (HH:mm:ss) and
 *    Android's external storage is FAT/exFAT-derived, where ':' fails the create with EPERM (measured on
 *    Droid 2 2026-08-21). The id INSIDE the file is untouched -- it is what gets read on a support call.
 *  - SWEEPMOVE-2026-08-21H: COPY-THEN-DELETE, never rename. renameTo() returns false on FUSE-mounted
 *    external storage when the move crosses into a subdirectory, and fails by RETURNING FALSE with no
 *    exception to catch. Order matters: the copy is verified before the original is deleted, so a
 *    part-way failure leaves a DUPLICATE, never a loss.
 *
 * ⛔ UNREADABLE IS LEFT IN PLACE, NEVER DELETED. "A manifest we cannot parse is still evidence."
 *
 * KILLED AREA IS RETURNED, NOT GLOBAL STATE. Android sets HomeStateImportController.killedImportArea,
 * an @Volatile var whose only reader is HomeStatePickerScreen:258. The shared port returns it and the
 * platform assigns, so nothing shared holds mutable app state. ⚠ As on Android, the LAST killed
 * manifest in the sweep wins.
 *
 * MUTABLE JSON REUSES THE EXISTING PATTERN (owner decision 2026-11-16 16:08): GtOsmLedger.Obj, already
 * documented as "org.json's mutable JSONObject: insertion order, put keeps an existing key's place",
 * with pretty() == GtOrgJson.pretty(json(), 2) == Android's json.toString(2). kotlinx's JsonObject is
 * immutable, so some wrapper is required; this is the one this codebase already certified.
 *
 * ⚠ ONE EXPECTED DIVERGENCE, recorded not hidden: GtOrgJson (kotlinx) is STRICT where org.json's
 * JSONTokener is LENIENT. A malformed manifest that Android would parse and sweep is logged here as
 * "not a manifest, left alone" and left in imports/. Unreachable in practice -- manifests are written by
 * the app itself through org.json / GtOrgJson, both of which emit well-formed JSON -- and the failure
 * direction is the safe one: a file is kept, never destroyed.
 *
 * ⚠ ANDROID STILL HAS ITS OWN COPY. HomeStateImportController.sweepManifests is unchanged by this
 * commit, so the two are identical by construction -- DO NOT EDIT ONE ALONE. Switching Android onto
 * this port is its own change, with its own build and its own three-column certification, exactly as
 * GtHttpAndroid carries the same note for GtHttp.
 */
object GtManifestSweep {

    private val log = gtLogger("GtManifestSweep")

    /** [swept] moved to history, [total] manifests considered, [killedArea] for the picker's banner. */
    data class Result(val swept: Int, val total: Int, val killedArea: String?)

    /**
     * @param importsDir GroupTrackStorage.dir("imports")'s analogue -- created by the caller.
     * @param nowIso     iso8601Now(): "yyyy-MM-dd'T'HH:mm:ss" in DEVICE LOCAL time, as 2.7e writes it.
     * @param logI/W/E   the platform's logger at 2.7e's three levels, so logcat reads the same.
     */
    fun run(
        fs: FileSystem,
        importsDir: Path,
        nowIso: String,
        logI: (String) -> Unit = { log.i { it } },
        logW: (String) -> Unit = { log.w { it } },
        logE: (String) -> Unit = { log.e { it } },
    ): Result {
        val history = importsDir / "history"
        try { fs.createDirectories(history) } catch (e: Exception) { }

        val entries = try { fs.list(importsDir) } catch (e: Exception) { return Result(0, 0, null) }

        // MARKERFIX-2026-08-25M: .json AND a `sources` array. The extension alone is not a manifest.
        val files = ArrayList<Path>()
        for (p in entries) {
            val md = try { fs.metadataOrNull(p) } catch (e: Exception) { null } ?: continue
            if (md.isDirectory) continue
            if (p.name.substringAfterLast('.', "") != "json") continue
            val ok = try {
                obj(fs, p).optArray("sources") != null
            } catch (e: Exception) {
                logW("sweep: ${p.name} is not a manifest, left alone")
                false
            }
            if (ok) files.add(p)
        }

        if (files.isEmpty()) {
            logI("sweep: imports/ is empty, nothing to do")
            return Result(0, 0, null)
        }

        var swept = 0
        var killedArea: String? = null

        for (f in files) {
            try {
                val o = obj(fs, f)
                val sources = o.optArray("sources")

                // "counted" = the source reported an outcome. Old manifests carry `imported` only;
                // new ones carry the five. Either satisfies it.
                var allCounted = sources != null && sources.size > 0
                if (sources != null) {
                    for (e in sources) {
                        val s = e as? JsonObject
                        if (s == null) { allCounted = false; break }
                        val counted = s.containsKey("imported") &&
                            GtOrgJson.optString(s, "status") == "completed"
                        if (!counted) { allCounted = false; break }
                    }
                }

                if (!allCounted) {
                    // STAMP FIRST.
                    o.put("process_state", "killed")
                    o.put("killed_at", nowIso)
                    // KILLEDIMPORT-2026-10-02: remember it -- the gate re-opens the picker, the picker says so.
                    val j = o.json()
                    killedArea = GtOrgJson.optString(j, "state", "")
                        .ifBlank { GtOrgJson.optString(j, "area", "") }
                        .ifBlank { "last" }
                    fs.write(f) { writeUtf8(o.pretty()) }
                }

                // THEN MOVE. Name by manifest_id where present -- it carries a timestamp, so two runs
                // on the same day stop colliding in history. No id means an old manifest: keep its name.
                val id = GtOrgJson.optString(o.json(), "manifest_id", "")
                val safeId = id.replace(':', '-')   // SWEEPNAME-2026-08-21I
                val target = if (safeId.isBlank()) history / f.name else history / "$safeId.json"

                // SWEEPMOVE-2026-08-21H: log the CLASSIFICATION before attempting the move. The older
                // log said only "could not move", which made a correct classification look broken.
                val verdict = if (allCounted) "completed" else "KILLED"
                logI("sweep: ${f.name} classified $verdict -> history/${target.name}")

                var moved = false
                try {
                    val sourceSize = fs.metadataOrNull(f)?.size ?: -1L
                    fs.write(target) { writeUtf8(fs.read(f) { readUtf8() }) }
                    val targetSize = fs.metadataOrNull(target)?.size ?: -2L
                    if (fs.exists(target) && targetSize == sourceSize) {
                        val deleted = try { fs.delete(f); true } catch (e: Exception) { false }
                        if (deleted) {
                            moved = true
                        } else {
                            // Copy is safe; the original would not delete. Say so plainly --
                            // next launch will see both.
                            logW("sweep: copied ${f.name} but could not delete the original -- " +
                                "duplicate left in imports/")
                        }
                    } else {
                        logW("sweep: copy of ${f.name} did not verify (target ${targetSize}B vs " +
                            "source ${sourceSize}B) -- original left in place")
                        try { fs.delete(target) } catch (e: Exception) { }
                    }
                } catch (e: Exception) {
                    logE("sweep: move of ${f.name} failed: ${e.message} -- original left in place, " +
                        "will retry next launch")
                }

                if (moved) swept++
            } catch (e: Exception) {
                // A manifest we cannot parse is still evidence. Never delete it.
                logE("sweep: ${f.name} unreadable, left in place: ${e.message}")
            }
        }

        logI("sweep: $swept of ${files.size} manifest(s) moved to history")
        return Result(swept, files.size, killedArea)
    }

    private fun obj(fs: FileSystem, p: Path): GtOsmLedger.Obj =
        GtOsmLedger.Obj.of(GtOrgJson.parse(fs.read(p) { readUtf8() }) as JsonObject)
}
