package com.geeksville.mesh.convoy

import android.util.Log

// ----------------------------------------------------------------
// ConvoyTrackSaveOps -- V2.5 Scaffold (Pass 1)
// GPS track save: parse GPX metadata, insert track row,
// trigger tile follow, share prompt, survey.
// Source: ScreenReference v5 section 7, Master Build Phase 0
// ----------------------------------------------------------------

object ConvoyTrackSaveOps {

    private const val TAG = "TrackSaveOps"

    /** Parse GPX file for bbox, distance, duration, point count */
    fun parseGpxMetadata(filePath: String) {
        Log.d(TAG, "parseGpxMetadata $filePath — Pass 1 stub")
    }

    /** Insert track row into spatial + extension db */
    fun insertTrackRow(name: String, filePath: String) {
        Log.d(TAG, "insertTrackRow '$name' $filePath — Pass 1 stub")
    }

    /** Create SPATIAL_FOLLOW tile queue job at HIGH priority */
    fun triggerTileFollow(trackId: String) {
        Log.d(TAG, "triggerTileFollow $trackId — Pass 1 stub")
    }

    /** Show save dialog: name field + SKIP/SAVE */
    fun stub() { /* Pass 1 scaffold */ }
}
