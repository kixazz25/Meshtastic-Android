package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtImportAndroid
import com.geeksville.mesh.grouptrack.GtOsmAndroid
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.osm.GtOsmLedger
import com.grouptrack.shared.osm.GtOsmStage
import kotlinx.serialization.json.JsonArray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * OsmImportLedger -- the run record for one state's OSM trail import. RULE: the ledger records WHAT HAPPENED, never WHERE YOU ARE;
 * disk wins (OsmImportStage.stageOf). Written .tmp + rename.
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR of the certified shared GtOsmLedger (2.8 step 15g, 57cd2a8a7) on the app's environment --
 * the same file, the same text (org.json's toString(2)). D16 (Fred 11-10): the extract record carries skinny_rows (the skinny's total
 * rows, counted by SQL) instead of skinny_bytes. read / create hand back org.json objects (parsed from the shared text) as 2.7e did;
 * a carried imports[] goes in as its text. The 2.7e file is in git history (57cd2a8a7) and in the TEST-ONLY OsmImportLedgerCertOld.
 */
object OsmImportLedger {
    const val FILENAME = GtOsmLedger.FILENAME
    const val HISTORY_FILENAME = GtOsmLedger.HISTORY_FILENAME

    private fun led(ctx: Context): GtOsmLedger { val env = GtImportAndroid(ctx); return GtOsmLedger(env, GtOsmStage(env, GtOsmAndroid(ctx))) }

    fun file(ctx: Context, slug: String): File = File(led(ctx).file(slug).toString())
    fun read(ctx: Context, slug: String): JSONObject? = led(ctx).read(slug)?.let { JSONObject(it.pretty()) }
    fun create(ctx: Context, slug: String, state: String, sourceNote: String, carryForwardImports: JSONArray? = null): JSONObject =
        JSONObject(led(ctx).create(slug, state, sourceNote, carryForwardImports?.let { GtOrgJson.parse(it.toString()) as JsonArray }).pretty())
    fun priorImports(ctx: Context, slug: String): JSONArray? = read(ctx, slug)?.optJSONArray("imports")
    fun recordAcquire(ctx: Context, slug: String, sourceFile: String, bytes: Long, movedFrom: String) = led(ctx).recordAcquire(slug, sourceFile, bytes, movedFrom)
    fun recordExtract(ctx: Context, slug: String, gpkgBytes: Long, classes: List<String>, candidateRows: Int, kept: Int, badGeometry: Int, skinnyRows: Long) =
        led(ctx).recordExtract(slug, gpkgBytes, classes, candidateRows, kept, badGeometry, skinnyRows)
    fun appendImport(
        ctx: Context, slug: String, scope: String, bboxOrNull: String?, found: Int, inserted: Int, aliased: Int, dropped: Int,
        geometryChanged: Int = 0, errors: Int = 0, pointsFound: Int = 0, pointsInserted: Int = 0, pointsDropped: Int = 0, pointsMoved: Int = 0
    ) = led(ctx).appendImport(slug, scope, bboxOrNull, found, inserted, aliased, dropped, geometryChanged, errors, pointsFound, pointsInserted, pointsDropped, pointsMoved)
    fun setPendingImport(ctx: Context, slug: String, scope: String, bbox: DoubleArray?) = led(ctx).setPendingImport(slug, scope, bbox)
    fun pendingScope(ctx: Context, slug: String): String? = led(ctx).pendingScope(slug)
    fun pendingBbox(ctx: Context, slug: String): DoubleArray? = led(ctx).pendingBbox(slug)
    fun setPendingBbox(ctx: Context, slug: String, s: Double, w: Double, n: Double, e: Double) = led(ctx).setPendingBbox(slug, s, w, n, e)
    fun clearPendingImport(ctx: Context, slug: String) = led(ctx).clearPendingImport(slug)
    fun archiveLedger(ctx: Context, slug: String): File? = led(ctx).archiveLedger(slug)?.let { File(it.toString()) }
    fun finalizeAndDelete(ctx: Context, slug: String, bytesReclaimed: Long, deleted: List<String>) = led(ctx).finalizeAndDelete(slug, bytesReclaimed, deleted)
}
