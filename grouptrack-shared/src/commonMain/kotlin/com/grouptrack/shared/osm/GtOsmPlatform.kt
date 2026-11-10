package com.grouptrack.shared.osm

import okio.Path

/**
 * STEP15G-2026-11-10 (2.8 step 15g): what the OSM extract + import (2.7e OsmExtractWorker / OsmImportWorker / OsmImportStage) does
 * that is the PLATFORM's own business, beyond GtImportEnv. INTERFACE ONLY -- Android's implementation is app-side (GtOsmAndroid.kt, at
 * the 15g switch) and runs 2.7e's code for each, word for word; the iPad's comes with the iPad app; the certification records the calls
 * and touches nothing (tests never delete files).
 * Kept apart from GtImportEnv on purpose: adding members there would change 15a-15f's certified implementations.
 */
interface GtOsmPlatform {
    /**
     * OsmImportStage.rootDir's once-per-process legacy rescue (OSM-LEGACY-MIGRATE-2026-07-28): files stranded in the old app-private
     * getExternalFilesDir()/osm move into [root]. Called on every root resolution, exactly where 2.7e calls it; the Android side keeps
     * 2.7e's own once-per-process flag. The iPad has no legacy folder (nothing to do).
     */
    fun onOsmRoot(root: Path)

    /** OsmImportStage.discardState's legacy sweep (OSM-CANCEL-SWEEPS-LEGACY-2026-07-28) for [slug], guarded as 2.7e guards it. */
    fun discardLegacy(slug: String)

    /** java.io.File.usableSpace of [dir]: the extract's free-space gate. */
    fun usableSpace(dir: Path): Long

    /**
     * OsmExtractWorker's download sweep (OSM-ZIPDEL-2026-07-30): after the skinny is published, the browser's copies of this state's
     * zip in the public Downloads folder are deleted. ⚠ Guarded by the caller exactly as 2.7e (a failure never fails the extract).
     */
    fun sweepDownloadedZips(slug: String)

    /**
     * The workers' setForegroundSafely(text): 2.7e still BUILDS the notification (and creates its channel) at every forced publish,
     * then hands it to a shim that does nothing (INPROCESS-2026-10-02). [worker] = "extract" | "import" (the channel and title differ).
     */
    fun foreground(worker: String, text: String)
}
