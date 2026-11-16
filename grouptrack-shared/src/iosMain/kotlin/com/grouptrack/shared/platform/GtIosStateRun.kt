package com.grouptrack.shared.platform

import com.grouptrack.shared.state.GtStateDataRun

/**
 * CERTVALUES-2026-11-16: StateData.Run on the iPad -- the in-flight answers and the refreshing set.
 *
 * Android (StateData.kt) holds these in a ConcurrentHashMap and a Collections.newSetFromMap over
 * another one. Member for member the behaviour here is identical, INCLUDING removeAnswer returning
 * the PREVIOUS value or null, which is Map.remove's contract and what the shared code reads.
 *
 * ⛔ ONE DIVERGENCE, STATED BECAUSE IT IS NOT REPRODUCIBLE TODAY AND WILL MATTER LATER:
 * Android's maps are THREAD-SAFE; these are not. Kotlin/Native has no ConcurrentHashMap, and adding
 * a locking dependency to the shared module for a service the iPad cannot yet call concurrently
 * would be the wrong trade now. Nothing on the iPad touches these from two threads because the iPad
 * has no background state import -- the moment it does, this class needs a lock and the harness
 * cannot tell you that, because a race is not a value difference.
 *
 * ⚠ Android's Run is a private OBJECT -- one instance for the process. This is a class, so whoever
 * builds the iPad's env must hold ONE instance; two would split the answers and the symptom would be
 * a state import that reports no answer for a slug it just answered.
 */
class IosStateDataRun : GtStateDataRun {

    private val answers = LinkedHashMap<String, Boolean>()
    private val refreshing = LinkedHashSet<String>()

    override fun putAnswer(slug: String, refresh: Boolean) { answers[slug] = refresh }

    /** ⚠ Map.remove's contract: the PREVIOUS value, or null when the key was absent. */
    override fun removeAnswer(slug: String): Boolean? = answers.remove(slug)

    override fun hasAnswer(slug: String): Boolean = answers.containsKey(slug)

    override fun addRefreshing(slug: String) { refreshing.add(slug) }

    override fun refreshing(): List<String> = refreshing.toList()

    override fun clearRefreshing() { refreshing.clear() }
}
