package com.geeksville.mesh.convoy

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * MapSourceManager — single source of truth for all tile source URLs.
 * Reads from map_sources.json in assets. Replaces all hardcoded URLs
 * in ConvoyConfig, ConvoyScreen, ConvoyMapViewerScreen, ConvoyViewModel,
 * and ConvoyTileDownloader.
 *
 * RULE: No tile URL exists anywhere except in map_sources.json.
 *       Every screen, every downloader, every interceptor reads from here.
 */
object MapSourceManager {

    data class TileLayer(
        val role: String,           // "base" or "overlay"
        val urlTemplate: String,
        val tileFormat: String,
        val cacheDir: String,
        val renderOrder: Int = 0
    )

    data class TileSource(
        val id: String,
        val producer: String,
        val mapType: String,
        val name: String,
        val shortLabel: String,
        val layers: List<TileLayer>,
        val requiresKey: Boolean = false,
        val apiKey: String? = null,
        val attribution: String = ""
    ) {
        val baseUrl: String get() = layers.firstOrNull { it.role == "base" }?.urlTemplate ?: ""
        val baseCacheDir: String get() = layers.firstOrNull { it.role == "base" }?.cacheDir ?: id
        val overlayLayers: List<TileLayer> get() = layers.filter { it.role == "overlay" }
        val allCacheDirs: List<String> get() = layers.map { it.cacheDir }
    }

    data class SlotConfig(
        val slot: Int,
        val sourceId: String,
        val legacyKey: String
    )

    private var sources: List<TileSource> = emptyList()
    private var defaultSlots: List<SlotConfig> = emptyList()
    private var initialized = false

    var activeSourceKey: String = "SAT"
        private set

    private var appContext: Context? = null

    private fun externalDir(): File {
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS
        ), "GroupTrack")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun externalJsonFile(): File = File(externalDir(), "map_sources.json")
    private fun apiKeysFile(): File = File(externalDir(), "api_keys.json")

    private var apiKeys: MutableMap<String, String> = mutableMapOf()

    fun init(context: Context) {
        if (initialized) return
        appContext = context
        try {
            // Always read from bundled asset — single source of truth
            val json = context.assets.open("map_sources.json")
                .bufferedReader().use { it.readText() }
            android.util.Log.i("MapSourceMgr", "Reading assets map_sources.json")
            val root = JSONObject(json)
            val sourcesArray = root.getJSONArray("sources")
            val parsed = mutableListOf<TileSource>()
            for (i in 0 until sourcesArray.length()) {
                val s = sourcesArray.getJSONObject(i)
                val layersArray = s.getJSONArray("layers")
                val layers = mutableListOf<TileLayer>()
                for (j in 0 until layersArray.length()) {
                    val l = layersArray.getJSONObject(j)
                    layers.add(TileLayer(
                        role = l.getString("role"),
                        urlTemplate = l.getString("url_template"),
                        tileFormat = l.optString("tile_format", "png"),
                        cacheDir = l.getString("cache_dir"),
                        renderOrder = l.optInt("render_order", 0)
                    ))
                }
                parsed.add(TileSource(
                    id = s.getString("id"),
                    producer = s.optString("producer", ""),
                    mapType = s.optString("map_type", ""),
                    name = s.getString("name"),
                    shortLabel = s.getString("short_label"),
                    layers = layers,
                    requiresKey = s.optBoolean("requires_key", false),
                    attribution = s.optString("attribution", "")
                ))
            }
            sources = parsed
            val slotsArray = root.getJSONArray("default_slots")
            val slots = mutableListOf<SlotConfig>()
            for (i in 0 until slotsArray.length()) {
                val sl = slotsArray.getJSONObject(i)
                slots.add(SlotConfig(
                    slot = sl.getInt("slot"),
                    sourceId = sl.getString("source_id"),
                    legacyKey = sl.getString("legacy_key")
                ))
            }
            defaultSlots = slots
            initialized = true
            loadApiKeys()
            android.util.Log.i("MapSourceMgr", "Loaded ${sources.size} sources, ${defaultSlots.size} slots, ${apiKeys.size} API keys")
        } catch (e: Exception) {
            android.util.Log.e("MapSourceMgr", "JSON load failed: ${e.message}")
            loadFallback()
        }
    }

    private fun loadFallback() {
        android.util.Log.w("MapSourceMgr", "Using hardcoded fallback")
        sources = listOf(
            TileSource("esri-imagery-overlays", "Esri", "HYB", "Esri Imagery + Roads + Labels", "SAT",
                listOf(
                    TileLayer("base", "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", "jpg", "SAT", 0),
                    TileLayer("overlay", "https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}", "png", "SAT_LABELS_TRANSPORT", 1),
                    TileLayer("overlay", "https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}", "png", "SAT_LABELS_PLACES", 2)
                )),
            TileSource("esri-topo", "Esri", "TOPO", "Esri World Topo", "TOPO",
                listOf(TileLayer("base", "https://services.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}", "png", "TOPO"))),
            TileSource("esri-usa-topo", "Esri", "TOPO", "Esri USA Topo Maps", "TOPO+",
                listOf(TileLayer("base", "https://server.arcgisonline.com/ArcGIS/rest/services/USA_Topo_Maps/MapServer/tile/{z}/{y}/{x}", "png", "TOPO+")))
        )
        defaultSlots = listOf(
            SlotConfig(1, "esri-imagery-overlays", "SAT"),
            SlotConfig(2, "esri-topo", "TOPO"),
            SlotConfig(3, "esri-usa-topo", "TOPO+")
        )
        initialized = true
    }

    /** 3 slot sources for map bar buttons: (legacyKey, shortLabel, baseUrl) */
    fun getSlotSources(): List<Triple<String, String, String>> {
        ensureInit()
        return defaultSlots.mapNotNull { slot ->
            sources.find { it.id == slot.sourceId }?.let {
                Triple(slot.legacyKey, it.shortLabel, it.baseUrl)
            }
        }
    }

    /** All slots with all layers for download */
    fun getDownloadSources(): List<Pair<String, List<TileLayer>>> {
        ensureInit()
        return defaultSlots.mapNotNull { slot ->
            sources.find { it.id == slot.sourceId }?.let {
                Pair(slot.legacyKey, it.layers)
            }
        }
    }

    fun getSourceByKey(legacyKey: String): TileSource? {
        ensureInit()
        val slot = defaultSlots.find { it.legacyKey == legacyKey }
        return slot?.let { s -> sources.find { it.id == s.sourceId } }
    }

    fun getOnlineUrl(legacyKey: String): String =
        getSourceByKey(legacyKey)?.baseUrl ?: ""

    fun getLocalUrl(legacyKey: String): String =
        ConvoyConfig.LOCAL_TILE_BASE + legacyKey + "/{z}/{x}/{y}.png"

    fun getOverlayLayers(legacyKey: String): List<TileLayer> =
        getSourceByKey(legacyKey)?.overlayLayers ?: emptyList()

    fun getAllOverlayLayers(): List<TileLayer> {
        ensureInit()
        return defaultSlots.mapNotNull { slot ->
            sources.find { it.id == slot.sourceId }
        }.flatMap { it.overlayLayers }
    }

    fun setActive(legacyKey: String) {
        activeSourceKey = legacyKey
    }

    fun getActiveOnlineUrl(): String = getOnlineUrl(activeSourceKey)
    fun getActiveLocalUrl(): String = getLocalUrl(activeSourceKey)

    /** Build JSON string for overlay layers to inject into WebView */
    fun getOverlayJson(legacyKey: String): String {
        val layers = getOverlayLayers(legacyKey)
        if (layers.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        layers.forEachIndexed { i, layer ->
            if (i > 0) sb.append(",")
            sb.append("{\"url\":\"" + layer.urlTemplate + "\",\"maxNativeZoom\":18,\"cacheDir\":\"" + layer.cacheDir + "\"}")
        }
        sb.append("]")
        return sb.toString()
    }

    /** Update which source is assigned to a slot and persist to external JSON */
    fun updateSlotSource(legacyKey: String, newSourceId: String) {
        ensureInit()
        val slotIndex = defaultSlots.indexOfFirst { it.legacyKey == legacyKey }
        if (slotIndex < 0) return
        val oldSlot = defaultSlots[slotIndex]
        defaultSlots = defaultSlots.toMutableList().apply {
            set(slotIndex, SlotConfig(oldSlot.slot, newSourceId, oldSlot.legacyKey))
        }
        saveExternalJson()
        android.util.Log.i("MapSourceMgr", "Slot $legacyKey updated to source $newSourceId")
    }

    /** Save current slot assignments to external JSON */
    private fun saveExternalJson() {
        // Disabled — asset is single source of truth
    }

    /** Load API keys from external file */
    private fun loadApiKeys() {
        try {
            val file = apiKeysFile()
            if (!file.exists()) return
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.keys().forEach { key -> apiKeys[key] = json.getString(key) }
        } catch (e: Exception) {
            android.util.Log.e("MapSourceMgr", "Failed to load API keys: ${e.message}")
        }
    }

    /** Save API key for a source */
    fun saveApiKey(sourceId: String, key: String) {
        apiKeys[sourceId] = key
        try {
            val json = JSONObject()
            apiKeys.forEach { (k, v) -> json.put(k, v) }
            apiKeysFile().writeText(json.toString(2), Charsets.UTF_8)
            android.util.Log.i("MapSourceMgr", "Saved API key for $sourceId")
        } catch (e: Exception) {
            android.util.Log.e("MapSourceMgr", "Failed to save API key: ${e.message}")
        }
    }

    /** Get API key for a source (empty string if not set) */
    fun getApiKey(sourceId: String): String = apiKeys[sourceId] ?: ""

    /** Check if a source is available (has required API key if needed) */
    fun isSourceAvailable(sourceId: String): Boolean {
        val source = sources.find { it.id == sourceId } ?: return false
        return !source.requiresKey || apiKeys.containsKey(sourceId)
    }

    /** Get all sources for UI display */
    fun getAllSources(): List<TileSource> {
        ensureInit()
        return sources
    }

    /** Get current slot assignments */
    fun getSlotAssignments(): List<SlotConfig> {
        ensureInit()
        return defaultSlots
    }

    /** Inject API key into URL template */
    fun resolveUrl(urlTemplate: String, sourceId: String): String {
        val key = apiKeys[sourceId] ?: ""
        return urlTemplate.replace("{key}", key)
    }

    private fun ensureInit() {
        if (!initialized) {
            android.util.Log.w("MapSourceMgr", "Not initialized, using fallback")
            loadFallback()
        }
    }
}
