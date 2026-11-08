package com.grouptrack.shared.imports

import com.grouptrack.shared.text.GtLocalTime
import okio.FileSystem
import okio.Path

/**
 * STEP15A-2026-11-08 (2.8 step 15): everything the shared import code needs from the platform -- INTERFACES ONLY. Android's
 * implementation is app-side (GtImportAndroid.kt); the iPad's comes with the iPad app; the certification gives a fixed one so a NEW run
 * repeats exactly (as GtRides' env did in step 14). The database connections are NOT here: they are passed per call (the Android front
 * door wraps SpatialDbManager's CURRENT handles with GtAppDb.wrap, so the certification's handle swap applies).
 */
interface GtImportEnv {
    val clock: GtClock
    val ids: GtIds
    val files: GtFiles
    val paths: GtImportPaths
    val http: GtHttp
    val trailClass: GtTrailClass
    val platform: GtImportPlatform
}

/** Time. 2.7e: Instant.now().toString(), LocalDateTime.now(), System.currentTimeMillis(). */
interface GtClock {
    /** java.time.Instant.now().toString() -- row timestamps (ingested_at, created_at). The iPad: GtTime.instantTextMs(epochMs()). */
    fun nowInstant(): String
    /** The device's local wall-clock time now (ledger "created", area file names, manifest names, the trail batch date). */
    fun localNow(): GtLocalTime
    /** System.currentTimeMillis(). */
    fun epochMs(): Long
}

/** java.util.UUID.randomUUID().toString(). */
interface GtIds {
    fun newId(): String
}

/**
 * Files through okio, plus what okio cannot do or does differently.
 *  - [setLastModified] / [lastModified]: java.io.File's -- okio has no way to SET a file's time (2.7e keeps a track's earliest <time> as
 *    its file time). lastModified = 0 when the file is missing, as java.io.File.
 *  - [list]: a directory's entries IN THE PLATFORM'S ORDER. okio's FileSystem.list() SORTS; java.io.File.listFiles() does not, and the
 *    track resync's order decides which of two same-geometry files becomes the track and which an alias (INVENTORY T8). Android = the
 *    listFiles order; the iPad = sorted (owner decision 11). Empty for a missing directory (listFiles() == null in 2.7e).
 */
interface GtFiles {
    val fs: FileSystem
    fun setLastModified(path: Path, epochMs: Long): Boolean
    fun lastModified(path: Path): Long
    fun list(dir: Path): List<Path>
}

/** Where GroupTrack keeps things -- resolved on each call (Android's GroupTrackStorage can change root per session). */
interface GtImportPaths {
    /** GroupTrackStorage.root(): Documents/GroupTrack or its internal twin. */
    fun groupTrackRoot(): Path
    /** ConvoyTrackOps.tracksDir(): my_tracks, a SIBLING of the root, created. */
    fun tracksDir(): Path
    /** GroupTrackStorage.dir(name): a named child of the root ("data/trail_areas", "osm", "imports", "config"). Not created. */
    fun dir(name: String): Path
    /** The app's cache directory (convert_staging, import_convert). */
    fun cacheDir(): Path
    /** The app's private files directory (gpx_staging). */
    fun filesDir(): Path
}

/** HTTP. Android = TrailImporter.httpGet's body (timeouts, User-Agent, error body logged, catch Throwable). Downloads come in step 16. */
interface GtHttp {
    /** GET [url]; the body text, or null on any failure (2.7e logs the reason). Connect timeout 15 s, read timeout [readTimeoutMs]. */
    fun get(url: String, readTimeoutMs: Int): String?
}

/** TrailClassifier.useOf(category) (owner decision 9: injected now, ported with step 8 in step 16). */
interface GtTrailClass {
    fun useOf(category: String): String
}

/** The platform's own actions the import calls at 2.7e's points. */
interface GtImportPlatform {
    /** A bundled asset's text (trail_sources.json, trailhead GeoJSON, osm_layers.json). */
    fun readAsset(name: String): String
    /** DownloadQueueManager.enqueueCorridor(context, geomHash, slot, replaceExisting): tiles queued. */
    fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int
    /** ImportConverter.saveToDownloads: the converted GPX copied to Download; returns the name it got. Throws as 2.7e throws. */
    fun saveToDownloads(file: Path): String
    /** RiderTrailWriter.trailsChanged.value = now: the maps reload their trails. */
    fun onTrailsChanged()
}
