package com.geeksville.mesh.comm

import org.json.JSONObject

// COMMCONFIG-2026-10-12: moved from convoy/RadioConfigScreen.kt to the comm side (P4.2) -- code unchanged. A radio's saves are
// Meshtastic profiles; GroupTrack reaches them through CommConfig (savedConfigs / previewSaved / applySaved).
/** Per-radio backups: one folder per radio id; names carry the date, time and the config applied. */
object RadioBackups {
    fun dir(context: android.content.Context, nodeId: String) =
        java.io.File(context.filesDir, "convoy_backups/$nodeId").also { it.mkdirs() }

    fun list(context: android.content.Context, nodeId: String): List<java.io.File> =
        dir(context, nodeId).listFiles { f -> f.isFile && f.extension == "cfg" }?.sortedByDescending { it.name.split("_").drop(1).take(2).joinToString("") }
            ?: emptyList()

    fun newFile(context: android.content.Context, nodeId: String, label: String): java.io.File {
        val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val slug = label.replace(Regex("[^A-Za-z0-9 '-]"), "").trim().replace(Regex("\\s+"), "-").take(40).ifEmpty { "config" }
        return java.io.File(dir(context, nodeId), "${nodeId}_${ts}_$slug.cfg")
    }

    /**
     * SAVETITLE-2026-09-25 (Fred): each save is titled with the RIDE it was applied from, exactly -- kept in a
     * companion file beside the .cfg (the file name can only hold a squeezed version of the title).
     */
    fun writeMeta(cfg: java.io.File, title: String, rideId: String?, verified: Boolean) {
        runCatching {
            java.io.File(cfg.path.removeSuffix(".cfg") + ".json").writeText(
                JSONObject().put("title", title).put("rideId", rideId ?: JSONObject.NULL)
                    .put("appliedAt", java.time.LocalDateTime.now().toString()).put("verified", verified).toString(2),
            )
        }
    }

    private fun meta(f: java.io.File): JSONObject? =
        runCatching { JSONObject(java.io.File(f.path.removeSuffix(".cfg") + ".json").readText()) }.getOrNull()

    /** The save's title: the ride title it was applied from (companion file), else the name's label. */
    fun title(f: java.io.File): String = meta(f)?.optString("title")?.takeIf { it.isNotBlank() }
        ?: f.nameWithoutExtension.split("_").drop(3).joinToString(" ").replace('-', ' ').ifEmpty { "backup" }

    /** The ride id the save came from (null for the default, "as found", or old saves). */
    fun rideId(f: java.io.File): String? = meta(f)?.optString("rideId")?.takeIf { it.isNotBlank() && it != "null" }

    /** "applied 2026-09-25 14:20 . verified" -- the detail line under the title. */
    fun detail(f: java.io.File): String {
        val p = f.nameWithoutExtension.split("_")
        val at = runCatching { val d = p[1]; val t = p[2]
            "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)} ${t.substring(0, 2)}:${t.substring(2, 4)}" }.getOrDefault("")
        val v = meta(f)?.let { if (it.optBoolean("verified", true)) " \u00b7 verified \u2713" else " \u00b7 NOT verified" }.orEmpty()
        return "applied $at$v"
    }

    /** "!0ba3deda_20260925_142011_GroupTrack-default.cfg" -> "2026-09-25 14:20 -- GroupTrack default". */
    fun describe(f: java.io.File): String {
        val p = f.nameWithoutExtension.split("_")
        return runCatching {
            val d = p[1]; val t = p[2]
            val label = title(f)
            "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)} ${t.substring(0, 2)}:${t.substring(2, 4)} \u2014 $label"
        }.getOrDefault(f.name)
    }
}
