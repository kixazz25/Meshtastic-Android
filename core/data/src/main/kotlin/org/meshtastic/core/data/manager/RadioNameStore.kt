package org.meshtastic.core.data.manager

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * RADIONAMES-2026-10-03 (GroupTrack, Fred) -- THE RADIO NAME TABLE, one per tablet.
 * Device id -> the LONG NAME LAST USED, so riders recognise radios by name instead of a node id or the long name the
 * node database recorded the first time a radio was heard. Kept for good (up to ~100 radios ever).
 * Updated automatically, ONLY ON CHANGE, every change stamped (when, by what): "TAK payload", "config written",
 * "radio at connect". No hand edits. Changes live in memory at once; the file (radio_names.json in GroupTrack's
 * private files) is written at CONNECT and at RECORD only -- safely, via a temporary file swapped in.
 */
object RadioNameStore {
    private const val TAG = "RadioNames"

    class Entry(
        val num: Int,
        var longName: String,
        var updatedAt: Long,
        var updatedBy: String,
        val firstSeen: Long,
        /** CODE RULE 1: null until this tablet has connected to the radio -- only then is its Bluetooth address known. */
        var btAddress: String?,
    )

    private val entries = ConcurrentHashMap<Int, Entry>()
    /** CODE RULE 1: null until load() has been given GroupTrack's files directory (the app does it at startup). */
    @Volatile private var file: File? = null
    @Volatile private var dirty = false
    /** RADIONAMES2-2026-10-04: where each save also COPIES the file, so adb can pull it on a release build.
     *  CODE RULE 1: null = no copy (external storage unavailable, or not set). Set by the app at startup. */
    @Volatile var mirrorDir: File? = null

    /** Reads radio_names.json once; later calls do nothing. */
    @Synchronized
    fun load(dir: File) {
        if (file != null) return
        val f = File(dir, "radio_names.json")
        file = f
        if (!f.exists()) return
        try {
            val arr = JSONObject(f.readText()).optJSONArray("radios") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val num = o.getInt("num")
                entries[num] = Entry(num, o.getString("longName"), o.optLong("updatedAt"), o.optString("updatedBy"),
                    o.optLong("firstSeen"), o.optString("bt").takeIf { it.isNotEmpty() })
            }
            android.util.Log.i(TAG, "RADIONAMES-2026-10-03 loaded ${entries.size} radios")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "RADIONAMES-2026-10-03 load failed: ${e.message}")
        }
    }

    /** The long name last used, or null when this radio is not in the table yet. */
    fun nameOf(num: Int): String? = entries[num]?.longName

    /** The long name last used for the radio this tablet connected to at that Bluetooth address (e.g. "xDB:92:..."). */
    fun nameForAddress(fullAddress: String): String? = entries.values.firstOrNull { it.btAddress == fullAddress }?.longName

    /** Only on change: true when the radio is new or its long name changed. */
    fun put(num: Int, longName: String, by: String): Boolean {
        val name = longName.trim()
        if (name.isEmpty()) return false
        val now = System.currentTimeMillis()
        val e = entries[num]
        if (e == null) {
            entries[num] = Entry(num, name, now, by, now, null)
            dirty = true
            android.util.Log.i(TAG, "RADIONAMES-2026-10-03 new radio !${"%08x".format(num)} = \"$name\" ($by)")
            return true
        }
        if (e.longName == name) return false
        android.util.Log.i(TAG, "RADIONAMES-2026-10-03 !${"%08x".format(num)} \"${e.longName}\" -> \"$name\" ($by)")
        e.longName = name
        e.updatedAt = now
        e.updatedBy = by
        dirty = true
        return true
    }

    /** RADIONAMES2-2026-10-04 v5: a Meshtastic radio's Bluetooth name ends in "_" + 4 hex digits, normally the last 4 of its
     *  device id ("T1000-E_de0a"). The long name last used for the ONE radio in the table that fits, else null. */
    fun nameForBluetoothName(btName: String): String? {
        val suffix = btName.substringAfterLast('_', "").lowercase()
        if (suffix.length != 4 || !suffix.all { it in "0123456789abcdef" }) return null
        return entries.values.filter { "%08x".format(it.num).endsWith(suffix) }.singleOrNull()?.longName
    }

    /** Remembers which Bluetooth address this radio answers on (known only once connected). */
    fun noteAddress(num: Int, fullAddress: String) {
        val e = entries[num] ?: return
        if (e.btAddress == fullAddress) return
        e.btAddress = fullAddress
        dirty = true
    }

    /** Writes the file if anything changed -- called at CONNECT and at RECORD. */
    @Synchronized
    fun save(reason: String) {
        val f = file ?: return
        if (!dirty) return
        try {
            val arr = JSONArray()
            entries.values.sortedBy { it.firstSeen }.forEach { e ->
                arr.put(JSONObject()
                    .put("id", "!" + "%08x".format(e.num))
                    .put("num", e.num)
                    .put("longName", e.longName)
                    .put("updatedAt", e.updatedAt)
                    .put("updatedBy", e.updatedBy)
                    .put("firstSeen", e.firstSeen)
                    .put("bt", e.btAddress ?: ""))
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("radios", arr).toString(1))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            dirty = false
            android.util.Log.i(TAG, "RADIONAMES-2026-10-03 saved ${entries.size} radios ($reason)")
            // RADIONAMES2-2026-10-04 (Fred: "just copy the doc over"): a copy adb can pull on a release build
            mirrorDir?.let { d -> try { f.copyTo(File(d, f.name), overwrite = true) } catch (x: Exception) {
                android.util.Log.e(TAG, "copy to ${d.path} failed: ${x.message}") } }
            // RADIONAMES2-2026-10-04: the whole table into the log (release builds cannot read the file from the PC)
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            entries.values.sortedBy { it.firstSeen }.forEach { e ->
                android.util.Log.i(TAG, "table: !${"%08x".format(e.num)} = \"${e.longName}\" (${e.updatedBy}, ${fmt.format(java.util.Date(e.updatedAt))})")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "RADIONAMES-2026-10-03 save failed ($reason): ${e.message}")
        }
    }
}
