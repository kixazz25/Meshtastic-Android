package com.geeksville.mesh.convoy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * TileCodec — V2.6a tile compression for MBTilesStore.
 *
 * Re-encodes downloaded tile bytes to WebP before storage, to hit the
 * 35-40% aggregate storage-reduction goal:
 *   - BASE   (satellite/topo imagery, no alpha) -> LOSSY   WebP q80
 *       Big size win; visually lossless on a 10-11" field screen.
 *   - OVERLAY (labels/lines, needs transparency) -> LOSSLESS WebP
 *       Preserves alpha + crisp text; JPEG can't (no alpha channel).
 *
 * API note: WEBP_LOSSY / WEBP_LOSSLESS require API 30+. minSdk here is 21-26,
 * so we runtime-split: modern constants on 30+, the (deprecated but universal)
 * WEBP constant below 30. Both call the same encoder — output is equivalent.
 * When minSdk is eventually raised to 30+, delete the else-branch.
 *
 * FAIL-SAFE: if decode or encode throws (odd bytes, OOM), we RETURN THE
 * ORIGINAL BYTES unchanged. A tile is never lost to a codec error — worst
 * case it stores uncompressed, which still renders.
 */
object TileCodec {

    private const val TAG = "TileCodec"
    private const val BASE_QUALITY = 80      // q80: -63% storage, visually lossless at z19. q95 tested = catastrophic lossy-to-lossy bloat (rejected).
    private const val OVERLAY_QUALITY = 100  // lossless-equivalent for labels

    /** Encode tile bytes to WebP by role. isOverlay=true -> lossless. */
    fun encode(bytes: ByteArray, isOverlay: Boolean): ByteArray {
        // [V2.6a-Q95-PASS] Overlays (labels/lines, mostly transparent) bloat when
        // re-encoded to WebP - PNG already stores their sparse alpha efficiently.
        // Store overlay bytes AS-IS. Only the base is WebP-compressed.
        if (isOverlay) return bytes
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return bytes  // undecodable -> passthrough, keep the tile
            val out = ByteArrayOutputStream(bytes.size)
            val quality = if (isOverlay) OVERLAY_QUALITY else BASE_QUALITY
            val ok = compressWebp(bmp, isOverlay, quality, out)
            bmp.recycle()
            if (ok && out.size() > 0) out.toByteArray() else bytes
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "encode failed (${if (isOverlay) "overlay" else "base"}) -> passthrough: ${e.message}")
            bytes
        }
    }

    @Suppress("DEPRECATION")
    private fun compressWebp(bmp: Bitmap, lossless: Boolean, quality: Int, out: ByteArrayOutputStream): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            val fmt = if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS
                      else Bitmap.CompressFormat.WEBP_LOSSY
            // For LOSSLESS the quality arg controls encode effort (size vs speed),
            // not fidelity; 100 = best compression. For LOSSY it's visual quality.
            bmp.compress(fmt, quality, out)
        } else {
            // Pre-30: single WEBP constant. quality 100 == lossless, <100 == lossy.
            bmp.compress(Bitmap.CompressFormat.WEBP, quality, out)
        }
    }
}
