package com.geeksville.mesh.grouptrack

import android.content.Context

/**
 * SEAM2ASSETS-2026-11-16: the Android side of seam 2's ASSET conversion.
 *
 * Replaces this, repeated at 8 call sites:
 *     context.assets.open(name).bufferedReader().use { it.readText() }
 * with the socket the shared code and the iPad already use.
 *
 * ⚠ THIS HOP IS ALREADY CERTIFIED, which is the difference from the paths conversion. certassets
 * (11-16 12:09) read all 13 bundled assets BOTH ways on Droid 1 -- ctx.assets.open directly, and
 * env.platform.readAsset through the socket -- and the text was byte-identical every time
 * (ASSET|<name>|socket-bytes SAME, 13 of 13). It also proved org.json and GtOrgJson flatten all
 * 11,808 leaves identically, which closes the divergence GtHelpLinks had recorded and nobody had run.
 *
 * ⛔ IT MUST THROW WHEN THE ASSET IS ABSENT, exactly as assets.open does. Callers depend on the
 * throw, not on a null or an empty string:
 *     GtHelpLinks.load wraps it in runCatching and falls back to an EMPTY link table
 *     GtMapSpec.spec() catches and returns null
 *     GeofabrikCatalog / MapSourceManager wrap their whole read in try
 * GtImportAndroid.platform.readAsset is assets.open verbatim, so the throw is preserved.
 */
object GtAssetsAndroid {

    /** context.assets.open(name).bufferedReader().use { it.readText() }, through the socket. */
    fun text(ctx: Context, name: String): String = GtImportAndroid(ctx).platform.readAsset(name)
}
