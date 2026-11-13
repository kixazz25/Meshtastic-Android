package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyTrackOps
import com.geeksville.mesh.convoy.HomeStateImportController
import com.geeksville.mesh.convoy.OwnershipReclass
import com.geeksville.mesh.convoy.SpatialDbManager
import com.geeksville.mesh.convoy.TrailClassifier
import com.geeksville.mesh.convoy.TrailFilterState
import com.grouptrack.shared.startup.GtHkDb
import com.grouptrack.shared.startup.GtHkEnv
import com.grouptrack.shared.startup.GtHkFile
import java.io.File

/**
 * SWITCH17G2F-2026-11-13 (2.8 step 17, G2f): the Android side of [GtHkEnv] -- the real collaborators
 * the shared startup housekeeping and database audit talk to on this platform.
 *
 * ⭐ THE SAME INTERFACE THE CERTIFICATION SCRIPTED. G2f proved the shared port makes the same
 * decisions as 2.7e when both are handed identical collaborators; this is where the real ones go in.
 * Nothing here decides anything -- every branch lives in the shared code.
 */
class GtHkAndroid(private val ctx: Context, private val tag: String = "Housekeeping") : GtHkEnv {

    // ⚠ THE TAG IS PER CALLER, NOT PER ENV. 2.7e logged housekeeping under "Housekeeping" and the
    // audit under "DbAudit", and `adb logcat -s DbAudit` depends on it. The first switch hardcoded
    // one tag and the audit's DBREPAIR lines came out tagged Housekeeping -- caught on the first
    // real launch, 11-13 12:16, which is the whole reason a switch gets verified on the device
    // rather than being treated as a formality.

    private class Db(private val db: SQLiteDatabase) : GtHkDb {
        override val path: String? get() = db.path

        // ⚠ DOES NOT SWALLOW. 2.7e lets the count's failure reach jobSchemaConverge's own catch
        // (which is how PIECE4_THROWS is certified); GtDbAuditPort.count() has its own try.
        override fun rawQueryInt(sql: String): Int =
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        override fun rawQueryRows(sql: String): List<List<String?>> =
            db.rawQuery(sql, null).use { c ->
                val out = ArrayList<List<String?>>()
                while (c.moveToNext()) out.add((0 until c.columnCount).map { c.getString(it) })
                out
            }

        override fun execSQL(sql: String, bindings: List<String>) {
            if (bindings.isEmpty()) db.execSQL(sql)
            else db.execSQL(sql, bindings.toTypedArray())
        }

        /** ⭐ 2.7e detects a missing column exactly this way: the probe throws (jobSchemaConverge). */
        override fun probeColumnThrows(col: String): Boolean = try {
            db.rawQuery("SELECT $col FROM trails LIMIT 1", null).use { it.moveToFirst() }
            false
        } catch (e: Exception) { true }
    }

    override fun now(): Long = System.currentTimeMillis()

    override fun initDb() { SpatialDbManager.init(ctx) }
    override fun dbDir(): String = SpatialDbManager.dbDir().path
    override fun spatialDb(): GtHkDb? = SpatialDbManager.getSpatialDb()?.let { Db(it) }
    override fun extDb(): GtHkDb? = SpatialDbManager.getExtensionDb()?.let { Db(it) }
    override fun spatialPath(): String? = SpatialDbManager.getSpatialDb()?.path
    override fun parseGpxTrackPoints(text: String): List<Pair<Double, Double>> =
        SpatialDbManager.parseGpxTrackPoints(text)
    override fun computeGeomHash(wkt: String): String = SpatialDbManager.computeGeomHash(wkt)

    override fun ensureDefaults() { TrailFilterState.ensureDefaults(ctx) }
    override fun mergeShippedCategories(): List<String> = TrailFilterState.mergeShippedCategories(ctx)

    override fun ownershipFilePath(): String = OwnershipReclass.ownershipFile().path
    override fun fileExists(path: String): Boolean = File(path).exists()
    override fun fileLength(path: String): Long = File(path).length()
    override fun fileDelete(path: String): Boolean = File(path).delete()

    // ⚠ The text is NOT loaded here -- see GtHkEnv.readText. 2.7e reads a track only after the
    // .gpx filter, the "has a row" check and the two-hour window have already skipped it.
    override fun trackFiles(): List<GtHkFile> =
        (ConvoyTrackOps.tracksDir().listFiles() ?: emptyArray())
            .filter { it.isFile }
            .map { GtHkFile(name = it.name, lastModified = it.lastModified(), length = it.length()) }

    override fun readText(f: GtHkFile): String = File(ConvoyTrackOps.tracksDir(), f.name).readText()
    override fun tracksDirName(): String = ConvoyTrackOps.tracksDir().name

    override fun useOf(cartoCode: String): String = TrailClassifier.useOf(cartoCode)

    override fun sweepManifests() { HomeStateImportController.sweepManifests(ctx) }

    override fun markerExists(name: String): Boolean = File(SpatialDbManager.dbDir(), name).exists()
    override fun markerWrite(name: String, body: String) {
        val m = File(SpatialDbManager.dbDir(), name)
        m.parentFile?.mkdirs()
        m.writeText(body)
    }

    override fun log(line: String) { Log.i(tag, line) }
}
