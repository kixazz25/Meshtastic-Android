package com.geeksville.mesh.grouptrack

import android.content.Context
import org.json.JSONObject

/**
 * SHELL-2026-10-22 (2.7c standard 5, Fred 10-22): every launch point and every function has a HELP link to the manual pages
 * that support it.
 *
 * The links are DATA, not code (Fred 09:47: "the manual pages are a placeholder; the documentation process must have a method to
 * update the function-to-manual link"). They live in assets/grouptrack_help_links.json:
 *
 *   { "links": { "<module id>": "manual#<page id>", "<module id>.<process id>": "manual#<page id>", ... } }
 *
 * The manual rebuild regenerates that file from the manual's page ids, so a new manual updates every HELP button without a code
 * change. Lookup: the process's own entry, else its module's entry (a group of functions shares one page), else the manual's
 * front page -- and the doc process reports every id that fell through as a missing page.
 */
object GtHelp {
    private const val ASSET = "grouptrack_help_links.json"
    @Volatile private var links: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        links = runCatching {
            val o = JSONObject(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }).getJSONObject("links")
            o.keys().asSequence().associateWith { k -> o.getString(k) }
        }.getOrElse { e -> android.util.Log.w("GtHelp", "SHELL-2026-10-22: $ASSET not read: ${e.message}"); emptyMap() }
        loaded = true
    }

    /** The document key the docs viewer opens for [id] ("map", "map.download", ...). */
    fun linkFor(id: String): String =
        links[id] ?: links[id.substringBefore(".")] ?: "manual".also {
            android.util.Log.i("GtHelp", "SHELL-2026-10-22: no manual page for '$id' -- opening the manual's front page")
        }
}
