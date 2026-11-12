package com.grouptrack.shared.settings

/**
 * STEP17G2B2-2026-11-12 (2.8 step 17, G2b-2): the ride settings (2.7e ConvoySettingsRepository, Jetpack DataStore "convoy_settings")
 * ported onto the settings socket -- the same keys, the same defaults, the same removed-cart rules. Fred 10:59: "let's finish in here,
 * less to do later if this is certified".
 *   signal_drop_minutes (2f) · signal_lost_minutes (10f) · off_track_miles (0.5f; "saved" = present) · admission_window_hours (1)
 *   removed_carts: a set of "nodeId|callsign|epochDay"; "for today" = the entries ending "|<today>", nodeId -> callsign
 * [epochDay] = today's day number (2.7e LocalDate.now().toEpochDay()). Flows stay in the Android repository (the screens read them).
 * [fillMissing] = the ONE-TIME copy from the old DataStore (CODE RULE 3): it only fills keys this store does not have yet, so a key
 * already saved here is never overwritten and a later start copies nothing.
 */
class GtRideSettings(private val settings: GtSettings, private val storeName: String, private val epochDay: () -> Long) {
    companion object {
        const val STORE = "convoy_settings"
        const val K_DROP = "signal_drop_minutes"
        const val K_LOST = "signal_lost_minutes"
        const val K_OFF = "off_track_miles"
        const val K_ADM = "admission_window_hours"
        const val K_REMOVED = "removed_carts"
    }
    private fun p() = settings.store(storeName)

    fun signalDropMinutes(): Float = p().getFloat(K_DROP, 2f)
    fun signalLostMinutes(): Float = p().getFloat(K_LOST, 10f)
    fun offTrackMiles(): Float = p().getFloat(K_OFF, 0.5f)
    /** CODE RULE 1: null = never saved -- ConvoyViewModel applies only a SAVED value (2.7e offTrackMilesSaved) */
    fun offTrackMilesSaved(): Float? = if (p().contains(K_OFF)) p().getFloat(K_OFF, 0.5f) else null
    fun admissionWindowHours(): Int = p().getInt(K_ADM, 1)
    private fun removed(): Set<String> = p().getStringSet(K_REMOVED, null) ?: emptySet()
    fun removedCartsForToday(): Map<String, String> {
        val today = epochDay().toString()
        return removed().filter { it.endsWith("|$today") }.associate { e -> val parts = e.split("|"); parts[0] to parts[1] }
    }
    fun setSignalDropMinutes(value: Float) = p().edit { putFloat(K_DROP, value) }
    fun setSignalLostMinutes(value: Float) = p().edit { putFloat(K_LOST, value) }
    fun setOffTrackMiles(value: Float) = p().edit { putFloat(K_OFF, value) }
    fun setAdmissionWindowHours(value: Int) = p().edit { putInt(K_ADM, value) }
    fun removeCart(nodeId: String, callsign: String) {
        val entry = "$nodeId|$callsign|${epochDay()}"
        val next = removed() + entry
        p().edit { putStringSet(K_REMOVED, next) }
    }
    fun clearRemovedCarts() = p().edit { putStringSet(K_REMOVED, emptySet()) }
    fun reinstateCart(nodeId: String) {
        val today = epochDay().toString()
        val next = removed().filterNot { it.startsWith("$nodeId|") && it.endsWith("|$today") }.toSet()
        p().edit { putStringSet(K_REMOVED, next) }
    }
    fun purgeStaleRemovedCarts() {
        val today = epochDay().toString()
        val next = removed().filter { it.endsWith("|$today") }.toSet()
        p().edit { putStringSet(K_REMOVED, next) }
    }
    /** the one-time copy: every key of [old] this store does not hold yet; returns how many were copied (types kept as DataStore held them) */
    fun fillMissing(old: Map<String, Any?>): Int {
        val s = p(); var n = 0
        s.edit {
            for ((k, v) in old) {
                if (s.contains(k)) continue
                when (v) {
                    is Float -> putFloat(k, v); is Int -> putInt(k, v); is Long -> putLong(k, v); is Boolean -> putBoolean(k, v)
                    is String -> putString(k, v); is Set<*> -> putStringSet(k, v.map { it.toString() }.toSet())
                    else -> continue
                }
                n++
            }
        }
        return n
    }
}
