package com.geeksville.mesh.convoy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * RIDEIMPORT2-2026-09-24 — hands an imported ride's GPX to the EXISTING import panel (Fred 09-24: reuse is
 * everything). The file receiver stores the ride file, stages the ride's GPX where the panel stages picked
 * files (filesDir/gpx_staging), and offers it here; ui/Main.kt then shows ConvoyTrackImportScreen with the
 * file already listed and ticked -- no picker. The panel imports it (route, trailhead, recipe, narrative),
 * downloads the chosen maps, and deletes the staged file as it always does.
 */
object RideImportLauncher {
    /** Null BY DESIGN (CODE RULE 1): there is usually no ride waiting to be imported. */
    var pending by mutableStateOf<java.io.File?>(null)
        private set
    fun offer(file: java.io.File) { pending = file }
    fun clear() { pending = null }
}
