package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyTrackOps
import com.geeksville.mesh.convoy.DownloadQueueManager
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.ImportConverter
import com.geeksville.mesh.convoy.RiderTrailWriter
import com.geeksville.mesh.convoy.TrailClassifier
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtHttp
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.imports.GtTrailClass
import com.grouptrack.shared.text.GtLocalTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * STEP15A-2026-11-08 (2.8 step 15): the Android implementation of the shared import code's env -- each part is exactly the 2.7e call
 * it replaces, so the shared importers behave on the Droid as the 2.7e bodies did. Nothing calls this yet (15b onwards).
 */
class GtImportAndroid(context: Context) : GtImportEnv {
    private val app: Context = context.applicationContext

    override val clock = object : GtClock {
        override fun nowInstant(): String = java.time.Instant.now().toString()
        override fun localNow(): GtLocalTime {
            val t = java.time.LocalDateTime.now()
            return GtLocalTime(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
        }
        override fun epochMs(): Long = System.currentTimeMillis()
    }

    override val ids = object : GtIds {
        override fun newId(): String = java.util.UUID.randomUUID().toString()
    }

    override val files = object : GtFiles {
        override val fs: FileSystem = FileSystem.SYSTEM
        override fun setLastModified(path: Path, epochMs: Long): Boolean = File(path.toString()).setLastModified(epochMs)
        override fun lastModified(path: Path): Long = File(path.toString()).lastModified()
        /** java.io.File.listFiles() ORDER (unsorted) -- see GtFiles.list. */
        override fun list(dir: Path): List<Path> = File(dir.toString()).listFiles()?.map { it.toOkioPath() } ?: emptyList()
    }

    override val paths = object : GtImportPaths {
        override fun groupTrackRoot(): Path = GroupTrackStorage.root(app).toOkioPath()
        override fun tracksDir(): Path = ConvoyTrackOps.tracksDir().toOkioPath()   // creates it, as 2.7e
        override fun dir(name: String): Path = GroupTrackStorage.dir(name, app).toOkioPath()
        override fun cacheDir(): Path = app.cacheDir.toOkioPath()
        override fun filesDir(): Path = app.filesDir.toOkioPath()
    }

    override val http: GtHttp = GtHttpAndroid

    override val trailClass = object : GtTrailClass {
        override fun useOf(category: String): String = TrailClassifier.useOf(category)
    }

    override val platform = object : GtImportPlatform {
        override fun readAsset(name: String): String = app.assets.open(name).bufferedReader().use { it.readText() }
        override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int =
            DownloadQueueManager.enqueueCorridor(app, geomHash, slot, replaceExisting)
        override fun saveToDownloads(file: Path): String = ImportConverter.saveToDownloads(app, File(file.toString()))
        override fun onTrailsChanged() { RiderTrailWriter.trailsChanged.value = System.currentTimeMillis() }
    }
}

/**
 * STEP15A-2026-11-08: TrailImporter.httpGet (2.7e lines 476-518) VERBATIM, as the shared code's GtHttp. In 15f TrailImporter's own
 * httpGet is replaced by a call to this (one copy again); until then the two are identical by construction -- do not edit one alone.
 */
object GtHttpAndroid : GtHttp {
    private const val TAG = "TrailImporter"   // the 2.7e tag, so logcat recordings read the same

    override fun get(url: String, readTimeoutMs: Int): String? {
        val urlStr = url
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "GroupTrack/2.5")
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "(no error body)"
                } catch (e2: Throwable) {
                    "(error body unreadable: " + e2.javaClass.simpleName + ")"
                }
                val trimmed = if (body.length > 2000) body.substring(0, 2000) + " ...[truncated]" else body
                Log.e(TAG, "HTTP " + code + " " + conn.responseMessage + " -- server said: " + trimmed)
                Log.e(TAG, "HTTP " + code + " for URL: " + urlStr)
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            Log.i(TAG, "HTTP " + code + " OK, " + text.length + " chars")
            text
        } catch (ex: Throwable) {
            Log.e(TAG, "HTTP FAILED [" + ex.javaClass.simpleName + "] " + ex.message)
            Log.e(TAG, "HTTP FAILED for URL: " + urlStr)
            null
        }
    }
}
