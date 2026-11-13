package com.geeksville.mesh.grouptrack

/**
 * STEP17G2F-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the sink the generated 2.7e copies log into, so OLD's log lines can be compared with NEW's.
 * 2.7e calls Log.d/i/w/e(TAG, msg); the copies call these, which record "msg" exactly as 2.7e
 * passed it -- the TAG is recorded separately, never concatenated, because 2.7e never concatenates it.
 */
object GtHkCertLog {
    private val lines = ArrayList<String>()
    private val probes = LinkedHashMap<String, String>()
    private val effects = ArrayList<String>()
    fun reset() { lines.clear(); probes.clear(); effects.clear() }
    fun all(): List<String> = lines.toList()

    /**
     * ⭐ THE RECORDING PROBES (piece 2 on). 2.7e's run() builds `missing` and `files - withRow` as
     * LOCAL variables and logs only .take(15) / .take(10) of them -- hash-order SAMPLES, which the
     * 11-13 simulation showed flip between platforms. The probes read the full collections out so
     * the DECISION can be compared instead of the sample. ⛔ They add an observation point; they
     * never touch control flow, and each one is counted in the copy's substitution manifest.
     */
    fun probe(key: String, value: String) { probes[key] = value }
    fun probes(): Map<String, String> = LinkedHashMap(probes)

    /** Statements the OLD side actually executed, in order -- compared against NEW's effects. */
    fun effect(s: String) { effects.add(s) }
    fun effects(): List<String> = effects.toList()
    fun d(tag: String, msg: String) { lines.add(msg) }
    fun i(tag: String, msg: String) { lines.add(msg) }
    fun w(tag: String, msg: String) { lines.add(msg) }
    fun e(tag: String, msg: String) { lines.add(msg) }
}
