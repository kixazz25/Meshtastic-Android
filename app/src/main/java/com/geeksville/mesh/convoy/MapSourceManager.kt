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

    // ---- COLUMNFILE-2026-08-03: USER COLUMN FILE (write-only in phase 1) ----
    // The user-owned column table. Holds slot -> source plus the active key and
    // the URL TEMPLATE per column. Never holds the sources array: that is what
    // made 79251ae72 necessary, because a stale copy served stale URLs.
    // PHASE 1: written and maintained, NOT read for resolution. defaultSlots is
    // still authoritative, so behaviour is unchanged.
    private fun columnFile(): File = File(externalDir(), "map_slots.json")

    private var columnFileVersion: Long = -1L
    private var usedFallback: Boolean = false
    private var columnsTerminated: MutableSet<String> = mutableSetOf()

    /** Installed version code, used to decide when the install pass runs. */
    private fun currentVersionCode(context: Context): Long = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) {
        android.util.Log.e("MapSourceMgr", "COLUMNFILE-2026-08-03: version code unavailable: ${e.message}")
        -1L
    }

    /**
     * Seed or maintain the column file. Called at the END of init(), after the
     * asset has parsed, so defaultSlots and sources are populated.
     *
     * UNREADABLE IS NOT ABSENT. If the file exists but cannot be read we log and
     * return without writing. Treating a read failure as "absent" is exactly the
     * create-if-missing shape that destroyed the spatial DB on 08-01, and
     * All-Files access has been observed reporting TRUE while denied.
     */
    private fun syncColumnFile(context: Context) {
        // COLUMNFILE-2026-08-03: HARD STOP. If the hardcoded fallback supplied the data,
        // the column file is NOT touched - not seeded, not updated, not expired.
        // Fallback data must never reach the user column table by any route.
        if (usedFallback) {
            android.util.Log.e("MapSourceMgr",
                "COLUMNFILE-2026-08-03: FALLBACK WAS USED - column file left untouched")
            return
        }
        val vc = currentVersionCode(context)
        val file = columnFile()

        if (file.exists()) {
            val existing = try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                android.util.Log.e("MapSourceMgr",
                    "COLUMNFILE-2026-08-03: column file present but UNREADABLE - not seeding, not overwriting: ${e.message}")
                return
            }
            columnFileVersion = existing.optLong("version_code", -1L)
            if (columnFileVersion == vc) {
                android.util.Log.i("MapSourceMgr",
                    "COLUMNFILE-2026-08-03: column file current (vc=$vc), no install pass")
                readTerminated(existing)
                return
            }
            android.util.Log.i("MapSourceMgr",
                "COLUMNFILE-2026-08-03: INSTALL PASS (file vc=$columnFileVersion -> app vc=$vc)")
            writeColumnFile(vc, existing)
        } else {
            android.util.Log.i("MapSourceMgr", "COLUMNFILE-2026-08-03: column file ABSENT - seeding from defaults")
            writeColumnFile(vc, null)
        }
    }

    private fun readTerminated(root: JSONObject) {
        columnsTerminated.clear()
        val arr = root.optJSONArray("columns") ?: return
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.optString("status", "active") == "terminated") {
                columnsTerminated.add(c.optString("legacy_key", ""))
            }
        }
        if (columnsTerminated.isNotEmpty()) {
            android.util.Log.w("MapSourceMgr",
                "COLUMNFILE-2026-08-03: TERMINATED SOURCE IN COLUMN(S): $columnsTerminated")
        }
    }

    /**
     * Write the column file. When [existing] is non-null this is the INSTALL PASS:
     * each column keeps its user-chosen source_id and only the url_template and
     * status are refreshed from the catalogue. Slot assignments are NEVER changed
     * here - a release may retire a source, it may not repoint a user's column.
     * [existing] is null on first-run seed, where there is no prior file to carry
     * assignments forward from; that is the only case it may be null.
     */
    private fun writeColumnFile(vc: Long, existing: JSONObject?) {
        try {
            val priorById = mutableMapOf<String, String>()
            val priorActive = existing?.optString("active", "") ?: ""
            existing?.optJSONArray("columns")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val k = c.optString("legacy_key", "")
                    val sid = c.optString("source_id", "")
                    if (k.isNotEmpty() && sid.isNotEmpty()) priorById[k] = sid
                }
            }

            columnsTerminated.clear()
            val cols = org.json.JSONArray()
            defaultSlots.forEach { slot ->
                // user assignment wins over the catalogue default
                val sourceId = priorById[slot.legacyKey] ?: slot.sourceId
                val src = sources.find { it.id == sourceId }
                val terminated = (src == null)
                if (terminated) columnsTerminated.add(slot.legacyKey)
                val o = JSONObject()
                o.put("slot", slot.slot)
                o.put("legacy_key", slot.legacyKey)
                o.put("source_id", sourceId)
                o.put("short_label", src?.shortLabel ?: "")
                o.put("url_template", src?.baseUrl ?: "")
                o.put("requires_key", src?.requiresKey ?: false)
                o.put("status", if (terminated) "terminated" else "active")
                cols.put(o)
            }

            val root = JSONObject()
            root.put("version_code", vc)
            root.put("active", if (priorActive.isNotEmpty()) priorActive else activeSourceKey)
            root.put("columns", cols)

            // atomic: temp then rename. A truncated column file read at worker
            // start is a mystery a week later.
            val dest = columnFile()
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                android.util.Log.e("MapSourceMgr", "COLUMNFILE-2026-08-03: rename failed, column file NOT updated")
                return
            }
            columnFileVersion = vc
            android.util.Log.i("MapSourceMgr",
                "COLUMNFILE-2026-08-03: wrote ${cols.length()} columns (vc=$vc, terminated=$columnsTerminated)")
        } catch (e: Exception) {
            android.util.Log.e("MapSourceMgr", "COLUMNFILE-2026-08-03: column write failed: ${e.message}")
        }
    }

    /** Columns whose assigned source no longer exists in the catalogue. */
    fun terminatedColumns(): Set<String> = columnsTerminated.toSet()

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
            // COLUMNFILE-2026-08-03: seed / maintain the user column file. WRITE ONLY in
            // phase 1 - nothing reads it for resolution yet.
            syncColumnFile(context)
        } catch (e: Exception) {
            android.util.Log.e("MapSourceMgr", "JSON load failed: ${e.message}")
            loadFallback()
        }
    }

    private fun loadFallback() {
        android.util.Log.w("MapSourceMgr", "Using hardcoded fallback")
        // COLUMNFILE-2026-08-03
        usedFallback = true
        android.util.Log.e("MapSourceMgr",
            "COLUMNFILE-2026-08-03: FALLBACK REQUESTED - hardcoded sources in use. "
            + "Column file will NOT be written. Caller: "
            + android.util.Log.getStackTraceString(Throwable()))
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
        // COLUMNFILE-2026-08-03: the active column is user state and must survive the
        // process. It was in-memory only, defaulting to "SAT" at :53.
        saveExternalJson()
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

    /** Save current slot assignments to the user column file. */
    private fun saveExternalJson() {
        // COLUMNFILE-2026-08-03: was an empty body that was still being called. It now
        // persists the user's column table. It does NOT write the sources array -
        // that is the distinction from the external file 79251ae72 removed.
        if (usedFallback) {
            android.util.Log.e("MapSourceMgr", "COLUMNFILE-2026-08-03: FALLBACK WAS USED - not persisting")
            return
        }
        val ctx = appContext
        if (ctx == null) {
            android.util.Log.w("MapSourceMgr", "COLUMNFILE-2026-08-03: no context, slot change not persisted")
            return
        }
        // COLUMNFILE-SAVEFIX-2026-08-03: pass null, NOT the on-disk file.
        // Passing the existing file made writeColumnFile apply its install-pass
        // rule (priorById wins over slot.sourceId), so the STALE on-disk source
        // overwrote the selection the user had just made. Verified on Droid 1:
        // "Slot SAT updated to source google-satellite" while the file still read
        // esri-imagery-overlays.
        // On the SAVE path defaultSlots is the truth - it was mutated moments ago
        // by updateSlotSource() - and activeSourceKey is the truth for the active
        // column. Only the INSTALL PASS may carry a prior assignment forward.
        writeColumnFile(currentVersionCode(ctx), null)
    }

    /** Current column file as JSON, or null when absent/unreadable. */
    private fun readColumnFileOrNull(): JSONObject? {
        return try {
            val f = columnFile()
            if (!f.exists()) null else JSONObject(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            android.util.Log.e("MapSourceMgr", "COLUMNFILE-2026-08-03: column read failed: ${e.message}")
            null
        }
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
