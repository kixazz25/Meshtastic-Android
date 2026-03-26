package com.geeksville.mesh.convoy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

// ============================================================
// ConvoyTileDownloader.kt
// NEW FILE — place at:
//   app/src/main/java/com/geeksville/mesh/convoy/ConvoyTileDownloader.kt
//
// Downloads map tiles from a remote URL template to local device
// storage for offline use. Coroutine-based, cancellable, reports
// progress via callback.
//
// Tile URL format support:
//   Esri SAT/TOPO  : .../tile/{z}/{y}/{x}      (y before x)
//   Carto RD       : .../{z}/{x}/{y}.png        (x before y)
//   Google HYB     : ...x={x}&y={y}&z={z}       (query params)
// ============================================================

data class DownloadSummary(
    val downloaded: Int,
    val failed: Int,
    val totalMB: Float
)

object ConvoyTileDownloader {

    private const val AVG_TILE_BYTES = 15_360L    // 15 KB average
    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S    = 15L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    // ── Build the full URL for a single tile ─────────────────
    fun buildTileUrl(tile: TileKey, sourceUrl: String): String {
        return sourceUrl
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
    }

    // ── Build local storage path for a tile ──────────────────
    // Returns: filesDir/tiles/{sourceName}/{z}/{x}/{y}.png
    fun tilePath(context: Context, sourceName: String, tile: TileKey): File {
        val dir = File(context.filesDir, "tiles/${sourceName}/${tile.z}/${tile.x}")
        dir.mkdirs()
        return File(dir, "${tile.y}.png")
    }

    // ── Download a single tile — retry once on failure ───────
    suspend fun downloadTile(url: String, dest: File): Boolean {
        repeat(2) { attempt ->
            if (!coroutineContext.isActive) return false
            try {
                val success = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        response.body?.bytes()?.let { bytes ->
                            dest.writeBytes(bytes)
                            true
                        } ?: false
                    } else {
                        response.close()
                        false
                    }
                }
                if (success) return true
            } catch (e: Exception) {
                if (attempt == 1) return false
                kotlinx.coroutines.delay(500)
            }
        }
        return false
    }

    // ── Download all tiles in batch ───────────────────────────
    // onProgress(downloaded, total, failCount) called after each tile.
    // Checks coroutineContext.isActive before each tile — cancellable.
    suspend fun downloadTiles(
        context: Context,
        tiles: List<TileKey>,
        sourceUrl: String,
        sourceName: String,
        onProgress: (downloaded: Int, total: Int, failCount: Int) -> Unit
    ): Result<DownloadSummary> {
        return try {
            var downloaded = 0
            var failed = 0
            val total = tiles.size

            for (tile in tiles) {
                if (!coroutineContext.isActive) {
                    // Cancelled — return partial summary as failure
                    return Result.failure(
                        kotlinx.coroutines.CancellationException("Download cancelled")
                    )
                }

                val dest = tilePath(context, sourceName, tile)

                // Skip tiles already on disk (resume support)
                if (dest.exists() && dest.length() > 0) {
                    downloaded++
                    onProgress(downloaded, total, failed)
                    continue
                }

                val url = buildTileUrl(tile, sourceUrl)
                val success = downloadTile(url, dest)

                if (success) downloaded++ else failed++
                onProgress(downloaded, total, failed)
            }

            val totalMB = (downloaded * AVG_TILE_BYTES) / (1024f * 1024f)
            Result.success(DownloadSummary(downloaded, failed, totalMB))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Delete all tiles for a source ────────────────────────
    fun deleteSource(context: Context, sourceName: String): Boolean {
        val dir = File(context.filesDir, "tiles/$sourceName")
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    // ── Count tiles and size for a downloaded source ─────────
    fun sourceInfo(context: Context, sourceName: String): Pair<Int, Float> {
        val dir = File(context.filesDir, "tiles/$sourceName")
        if (!dir.exists()) return Pair(0, 0f)
        var count = 0
        var bytes = 0L
        dir.walkTopDown().filter { it.isFile }.forEach {
            count++
            bytes += it.length()
        }
        return Pair(count, bytes / (1024f * 1024f))
    }
}
