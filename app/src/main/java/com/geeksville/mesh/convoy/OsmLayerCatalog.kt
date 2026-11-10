package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtImportAndroid
import com.grouptrack.shared.osm.GtOsmLayers
import java.io.File

/**
 * What C2 (REDUCE) extracts from the Geofabrik GPKG into the skinny DB (OSM-C2-CATALOG-2026-07-28): override-first
 * (GroupTrackStorage.dir("config")/osm_layers.json), else the bundled asset; a broken override falls back.
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR of the certified shared GtOsmLayers (2.8 step 15g, 57cd2a8a7) on the app's environment
 * (GtImportAndroid: the same config folder and asset). The public API is 2.7e's, unchanged; the 2.7e file (why it is data, the CODE
 * RULE 1 note on filterColumn, the validation reasons) is in git history (57cd2a8a7) and in the TEST-ONLY OsmLayerCatalogCertOld.
 */
object OsmLayerCatalog {
    const val GEOM_LINE = GtOsmLayers.GEOM_LINE
    const val GEOM_POINT = GtOsmLayers.GEOM_POINT

    /** CODE RULE 1 -- filterColumn nullable: a point layer legitimately has no subset (2.7e's note, unchanged). */
    data class Layer(
        val id: String, val label: String, val enabled: Boolean, val required: Boolean,
        val sourceTable: String, val geometry: String, val targetTable: String,
        val columns: List<String>, val filterColumn: String?, val filterValues: List<String>
    ) {
        val isLine: Boolean get() = geometry == GEOM_LINE
        val isPoint: Boolean get() = geometry == GEOM_POINT
    }

    private fun mine(l: GtOsmLayers.Layer) = Layer(l.id, l.label, l.enabled, l.required, l.sourceTable, l.geometry, l.targetTable, l.columns, l.filterColumn, l.filterValues)
    private fun theirs(l: Layer) = GtOsmLayers.Layer(l.id, l.label, l.enabled, l.required, l.sourceTable, l.geometry, l.targetTable, l.columns, l.filterColumn, l.filterValues)

    fun overrideFile(): File = File(GroupTrackStorage.dir("config"), "osm_layers.json")
    fun load(ctx: Context): List<Layer> = GtOsmLayers(GtImportAndroid(ctx)).load().map { mine(it) }
    fun enabledLayers(ctx: Context): List<Layer> = load(ctx).filter { it.enabled }
    fun parse(text: String): List<Layer> = GtOsmLayers.parse(text).map { mine(it) }
    fun validate(layers: List<Layer>) = GtOsmLayers.validate(layers.map { theirs(it) })
}
