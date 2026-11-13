package com.grouptrack.shared.help
// STEP17G2G-2026-11-13: the shared port of 2.7e GtHelp (2.8 step 17, group G2g)

import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.jsonObject

/**
 * The HELP link table, shared.
 *
 * SHELL-2026-10-22 (2.7c standard 5, Fred 10-22): every launch point and every function has a HELP
 * link to the manual pages that support it.
 *
 * ⭐ THE LINKS ARE DATA, NOT CODE (Fred 09:47: "the manual pages are a placeholder; the
 * documentation process must have a method to update the function-to-manual link"). They live in
 * grouptrack_help_links.json:
 *
 *   { "links": { "<module id>": "manual#<page id>", "<module id>.<process id>": "...", ... } }
 *
 * The manual rebuild regenerates that file from the manual's page ids, so a new manual updates
 * every HELP button without a code change.
 *
 * ⛔ LOOKUP ORDER, AND IT IS THE WHOLE SERVICE: the process's own entry, else its MODULE's entry
 * (a group of functions shares one page), else the manual's front page -- and the doc process
 * reports every id that fell through as a missing page.
 *
 * ⚠ ONE KNOWN DIVERGENCE, FOLLOWING THE GtConfigs PRECEDENT (which met it first): 2.7e parses with
 * org.json, which is LENIENT; this parses with GtOrgJson, which is strict. A malformed asset that
 * Android would tolerate is rejected here -- and both sides then fall back to an empty table, so
 * every id gets the front page. Not reproduced, recorded.
 */
object GtHelpLinks {

    const val ASSET = "grouptrack_help_links.json"
    private val log = gtLogger("GtHelp")

    @Volatile private var links: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    /** ⚠ TEST-ONLY (CODE RULE 3): the cert exercises the once-only latch more than once. */
    fun resetForCert() { links = emptyMap(); loaded = false }

    /** Reads the asset once per process. ⚠ A failure is logged and leaves an EMPTY table, never a throw. */
    fun load(assets: GtHelpAssets) {
        if (loaded) return
        links = runCatching {
            val o = GtOrgJson.parse(assets.text(ASSET)).jsonObject["links"]!!.jsonObject
            o.keys.associateWith { k -> GtOrgJson.valueText(o[k]) ?: "" }
        }.getOrElse { e ->
            log.w { "SHELL-2026-10-22: $ASSET not read: ${e.message}" }
            emptyMap()
        }
        loaded = true
    }

    /** ⚠ not 2.7e: the harness compares the table SIZE, which 2.7e keeps private. */
    fun size(): Int = links.size

    /** MENUOVERVIEW-2026-11-04: true when the manual has a section of its own for [id]. */
    fun has(id: String): Boolean = links.containsKey(id)

    /** The document key the docs viewer opens for [id] ("map", "map.download", ...). */
    fun linkFor(id: String): String =
        links[id] ?: links[id.substringBefore(".")] ?: "manual".also {
            log.i { "SHELL-2026-10-22: no manual page for '$id' -- opening the manual's front page" }
        }
}

/**
 * The asset seam. ⚠ [text] must THROW when the asset is missing, exactly as 2.7e's
 * ctx.assets.open(...) does -- load()'s runCatching is what turns that into an empty table.
 */
interface GtHelpAssets {
    fun text(name: String): String
}
