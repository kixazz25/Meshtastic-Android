package com.geeksville.mesh.grouptrack

import android.content.Context
import com.grouptrack.shared.help.GtHelpAssets
import com.grouptrack.shared.help.GtHelpLinks

/**
 * SWITCH17G2G-2026-11-13 (2.8 step 17, G2g): front door to the shared GtHelpLinks (certified by
 * help17g2g -- 63 keys, 0 DIFF, both devices, twice).
 *
 * SHELL-2026-10-22 (2.7c standard 5, Fred 10-22): every launch point and every function has a HELP
 * link to the manual pages that support it. ⭐ The links are DATA, not code -- the manual rebuild
 * regenerates assets/grouptrack_help_links.json from the manual's page ids, so a new manual updates
 * every HELP button without a code change. The lookup rule (the process's own entry, else its
 * MODULE's entry, else the manual's front page) now lives in GtHelpLinks.
 *
 * Every public name is kept: GtHelpButton, GtHelpView and the forty-odd screens that call them are
 * unchanged.
 *
 * ⚠ ONE KNOWN DIVERGENCE, following the GtConfigs precedent: 2.7e parsed with org.json, which is
 * LENIENT; the shared table parses with GtOrgJson, which is strict. A malformed asset Android would
 * have tolerated is rejected -- and both then fall back to an EMPTY table, so every id gets the
 * front page, which is 2.7e's own behaviour for an unreadable asset. Certified: a non-string value
 * renders identically on both (g2g.number|id=route -> "7").
 */
object GtHelp {

    fun load(ctx: Context) = GtHelpLinks.load(AndroidAssets(ctx))

    /** MENUOVERVIEW-2026-11-04: true when the manual has a section of its own for [id]. */
    fun has(id: String): Boolean = GtHelpLinks.has(id)

    /** The document key the docs viewer opens for [id] ("map", "map.download", ...). */
    fun linkFor(id: String): String = GtHelpLinks.linkFor(id)

    /** ⚠ THROWS when the asset is absent, exactly as 2.7e's ctx.assets.open did -- the shared
     *  load()'s runCatching is what turns that into an empty table and a logged warning. */
    private class AndroidAssets(private val ctx: Context) : GtHelpAssets {
        override fun text(name: String): String =
            ctx.assets.open(name).bufferedReader().use { it.readText() }
    }
}
