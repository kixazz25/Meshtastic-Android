package com.geeksville.mesh.grouptrack

import com.grouptrack.shared.help.GtHelpAssets
import org.json.JSONObject

/**
 * STEP17G2G-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * 2.7e's GtHelp, running as REAL ANDROID CODE -- real org.json, real lenient parsing -- so the
 * Droid comparison is 2.7e against the shared port rather than one transcription against another.
 *
 * ══════════════════════════════════════════════════════════════════
 *  SUBSTITUTION MANIFEST -- G2g. Counted. Control flow untouched.
 * ══════════════════════════════════════════════════════════════════
 *   1x  object GtHelp                                 ->  object GtHelpCertOld
 *   1x  ctx.assets.open(ASSET).bufferedReader()...    ->  assets.text(ASSET)   (the same seam the
 *                                                          shared side takes; it THROWS when absent,
 *                                                          exactly as assets.open does)
 *   2x  android.util.Log.w / .i                       ->  GtHelpCertLog.w / .i
 *   1x  resetForCert()                                ->  ADDED: the cert exercises the once-only
 *                                                          latch more than once in one process
 * Everything else is byte-for-byte 2.7e, @Volatile included.
 */
object GtHelpCertOld {
    private const val ASSET = "grouptrack_help_links.json"
    @Volatile private var links: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    fun resetForCert() { links = emptyMap(); loaded = false }

    fun load(assets: GtHelpAssets) {
        if (loaded) return
        links = runCatching {
            val o = JSONObject(assets.text(ASSET)).getJSONObject("links")
            o.keys().asSequence().associateWith { k -> o.getString(k) }
        }.getOrElse { e -> GtHelpCertLog.w("GtHelp", "SHELL-2026-10-22: $ASSET not read: ${e.message}"); emptyMap() }
        loaded = true
    }

    fun has(id: String): Boolean = links.containsKey(id)

    fun linkFor(id: String): String =
        links[id] ?: links[id.substringBefore(".")] ?: "manual".also {
            GtHelpCertLog.i("GtHelp", "SHELL-2026-10-22: no manual page for '$id' -- opening the manual's front page")
        }

    /** ⚠ not 2.7e: the harness compares the table SIZE, which 2.7e keeps private. */
    fun size(): Int = links.size
}

/** TEST-ONLY: the sink the copy logs into. */
object GtHelpCertLog {
    private val lines = ArrayList<String>()
    fun reset() { lines.clear() }
    fun all(): List<String> = lines.toList()
    fun w(tag: String, msg: String) { lines.add("W $msg") }
    fun i(tag: String, msg: String) { lines.add("I $msg") }
}
