package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyConfig
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.ImportConverter

/**
 * IPADEXPORT-2026-11-06 (Fred 11-06 "move all the data over and be done with it" -- 2.8 iPad work): copy the map stores to Download
 * so adb can take them to the Mac. Android keeps the .mbtiles the app made private (-rw-------); everything else in the GroupTrack
 * folder adb can already copy (done 11-06, 1.6 GB from Droid 1).
 *  - runs ONLY when GroupTrack/ipad_export.request exists (adb shell touch ...); that file is deleted FIRST, so one request = one run;
 *  - copies VEC.mbtiles and DEM.mbtiles to Download (as ImportConverter.saveToDownloads does for GPX); the stores are only read;
 *  - on its own thread -- the app starts normally; log tag IpadExport.
 * CODE RULE 3: test-only -- REMOVE before 2.8 is released.
 */
object GtIpadExport {
    private const val TAG = "IpadExport"

    fun runIfRequested(ctx: Context) {
        val request = GroupTrackStorage.dir("ipad_export.request", ctx)
        if (!request.exists()) return
        val deleted = request.delete()
        Log.i(TAG, "IPADEXPORT request found (deleted=$deleted) -- copying the map stores to Download")
        val app = ctx.applicationContext
        Thread {
            for (slot in listOf("VEC", "DEM")) {
                val f = java.io.File(ConvoyConfig.TILE_DIR, "$slot.mbtiles")
                if (!f.exists()) { Log.i(TAG, "IPADEXPORT $slot: not on this tablet"); continue }
                try {
                    val t0 = System.currentTimeMillis()
                    val name = ImportConverter.saveToDownloads(app, f)
                    Log.i(TAG, "IPADEXPORT $slot: ${f.length()} B -> Download/$name in ${(System.currentTimeMillis() - t0) / 1000} s")
                } catch (e: Exception) {
                    Log.e(TAG, "IPADEXPORT $slot failed: ${e.message}")
                }
            }
            Log.i(TAG, "IPADEXPORT done")
        }.start()
    }
}
