package com.grouptrack.shared.cert
// STEP17G2G-2026-11-13: installed for step 17 group G2g (the HELP link table)

import com.grouptrack.shared.help.GtHelpAssets
import com.grouptrack.shared.help.GtHelpFixture
import com.grouptrack.shared.help.GtHelpLinks

/**
 * STEP17G2G-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * the G2g cert script (the HELP link table), ONE copy for every side.
 *
 * ⛔ WHAT IS COMPARED: the table SIZE after each load, and for every probe id both has(id) and
 * linkFor(id) -- which together pin the whole lookup rule (exact entry, else the MODULE's entry,
 * else the manual's front page) and the once-only load latch.
 *
 * ⛔ WHAT IS NOT: the log lines. Their text carries the PARSER'S exception message, and 2.7e uses
 * org.json while the shared side uses GtOrgJson -- comparing those would compare the two parsers'
 * wording, not the service. That a failure produced an EMPTY table is the decision, and size==0
 * plus every id falling through to "manual" says it.
 *
 * ⚠ THE LENIENCY DIVERGENCE IS SCRIPTED, NOT ASSUMED. GtConfigs met it first: org.json is lenient,
 * GtOrgJson is strict. "malformed" is bad enough that BOTH reject it; "number" puts a non-string
 * value in the table on purpose, so the run ANSWERS whether getString and valueText agree rather
 * than me deciding beforehand that the asset only ever holds strings.
 */
object Cert17g2g {

    interface Side {
        fun reset()
        /** [missing] makes the asset seam throw, exactly as ctx.assets.open does for an absent file. */
        fun load(text: String, missing: Boolean)
        fun size(): Int
        fun has(id: String): Boolean
        fun linkFor(id: String): String
    }

    fun run(side: Side, emit: (String, String) -> Unit) {
        for ((name, text) in GtHelpFixture.CASES) {
            side.reset()
            side.load(text, missing = false)
            emit("g2g.$name|size", "${side.size()}")
            for (id in GtHelpFixture.PROBE_IDS)
                emit("g2g.$name|id=$id", "${side.has(id)}|${side.linkFor(id)}")
        }

        // the asset is not there at all -> the seam throws -> runCatching -> an empty table
        side.reset()
        side.load("", missing = true)
        emit("g2g.missing|size", "${side.size()}")
        for (id in GtHelpFixture.PROBE_IDS)
            emit("g2g.missing|id=$id", "${side.has(id)}|${side.linkFor(id)}")

        // ⭐ THE ONCE-ONLY LATCH: load a good table, then a DIFFERENT one. 2.7e returns at `if
        // (loaded) return`, so the second load must change nothing -- the table stays the first.
        side.reset()
        side.load(GtHelpFixture.GOOD, missing = false)
        side.load(GtHelpFixture.EMPTY, missing = false)
        emit("g2g.latch|size", "${side.size()}")
        emit("g2g.latch|map", side.linkFor("map"))
        emit("g2g.latch|has_map", "${side.has("map")}")
    }

    /** The asset seam over a scripted string. */
    class TextAssets(private val text: String, private val missing: Boolean) : GtHelpAssets {
        override fun text(name: String): String =
            if (missing) throw IllegalStateException("cert: $name is not in the assets") else text
    }

    /** NEW: the shared table. The same class runs on the Droid and the iPad. */
    class NewSide : Side {
        override fun reset() = GtHelpLinks.resetForCert()
        override fun load(text: String, missing: Boolean) = GtHelpLinks.load(TextAssets(text, missing))
        override fun size(): Int = GtHelpLinks.size()
        override fun has(id: String): Boolean = GtHelpLinks.has(id)
        override fun linkFor(id: String): String = GtHelpLinks.linkFor(id)
    }
}
