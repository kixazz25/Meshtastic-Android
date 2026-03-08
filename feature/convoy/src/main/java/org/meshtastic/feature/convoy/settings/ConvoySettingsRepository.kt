package org.meshtastic.feature.convoy.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.convoyDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "convoy_settings")

@Singleton
class ConvoySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // ── Preference Keys ───────────────────────────────────────────────────────

    companion object {
        // Alert thresholds
        val KEY_SIGNAL_DROP_DBM        = floatPreferencesKey("signal_drop_dbm")
        val KEY_SIGNAL_LOST_MINUTES    = intPreferencesKey("signal_lost_minutes")
        val KEY_OFF_TRACK_METERS       = intPreferencesKey("off_track_meters")

        // Node filter
        val KEY_ADMISSION_WINDOW_HOURS = intPreferencesKey("admission_window_hours")

        // Removed carts — stored as Set<String> of nodeIds removed today
        // Format: "nodeId|calendarDayEpoch" so it auto-expires across midnight
        val KEY_REMOVED_CARTS          = stringSetPreferencesKey("removed_carts")

        // Defaults
        const val DEFAULT_SIGNAL_DROP_DBM        = -120f   // dBm floor
        const val DEFAULT_SIGNAL_LOST_MINUTES    = 10      // minutes
        const val DEFAULT_OFF_TRACK_METERS       = 200     // meters
        const val DEFAULT_ADMISSION_WINDOW_HOURS = 1       // hours (within today)
    }

    // ── Flows ─────────────────────────────────────────────────────────────────

    val signalDropDbm: Flow<Float> = context.convoyDataStore.data
        .map { it[KEY_SIGNAL_DROP_DBM] ?: DEFAULT_SIGNAL_DROP_DBM }

    val signalLostMinutes: Flow<Int> = context.convoyDataStore.data
        .map { it[KEY_SIGNAL_LOST_MINUTES] ?: DEFAULT_SIGNAL_LOST_MINUTES }

    val offTrackMeters: Flow<Int> = context.convoyDataStore.data
        .map { it[KEY_OFF_TRACK_METERS] ?: DEFAULT_OFF_TRACK_METERS }

    val admissionWindowHours: Flow<Int> = context.convoyDataStore.data
        .map { it[KEY_ADMISSION_WINDOW_HOURS] ?: DEFAULT_ADMISSION_WINDOW_HOURS }

    /** Raw removed cart entries — callers should filter via [getRemovedCartIdsForToday] */
    private val rawRemovedCarts: Flow<Set<String>> = context.convoyDataStore.data
        .map { it[KEY_REMOVED_CARTS] ?: emptySet() }

    /** Node IDs removed today only — entries from previous days are silently ignored */
    val removedCartIdsForToday: Flow<Set<String>> = rawRemovedCarts.map { entries ->
        val todayKey = todayEpochDay()
        entries
            .filter { it.endsWith("|$todayKey") }
            .map { it.substringBefore("|") }
            .toSet()
    }

    // ── Writers ───────────────────────────────────────────────────────────────

    suspend fun setSignalDropDbm(value: Float) {
        context.convoyDataStore.edit { it[KEY_SIGNAL_DROP_DBM] = value }
    }

    suspend fun setSignalLostMinutes(value: Int) {
        context.convoyDataStore.edit { it[KEY_SIGNAL_LOST_MINUTES] = value }
    }

    suspend fun setOffTrackMeters(value: Int) {
        context.convoyDataStore.edit { it[KEY_OFF_TRACK_METERS] = value }
    }

    suspend fun setAdmissionWindowHours(value: Int) {
        context.convoyDataStore.edit { it[KEY_ADMISSION_WINDOW_HOURS] = value }
    }

    /** Remove a cart for today. Cannot be undone until midnight. */
    suspend fun removeCart(nodeId: String) {
        val entry = "$nodeId|${todayEpochDay()}"
        context.convoyDataStore.edit { prefs ->
            val current = prefs[KEY_REMOVED_CARTS] ?: emptySet()
            prefs[KEY_REMOVED_CARTS] = current + entry
        }
    }

    /** Reinstate a previously removed cart for today. */
    suspend fun reinstateCart(nodeId: String) {
        val entry = "$nodeId|${todayEpochDay()}"
        context.convoyDataStore.edit { prefs ->
            val current = prefs[KEY_REMOVED_CARTS] ?: emptySet()
            prefs[KEY_REMOVED_CARTS] = current - entry
        }
    }

    /** Purge all removed cart entries from previous days (call on app start). */
    suspend fun purgeStaleRemovedCarts() {
        val todayKey = todayEpochDay()
        context.convoyDataStore.edit { prefs ->
            val current = prefs[KEY_REMOVED_CARTS] ?: emptySet()
            prefs[KEY_REMOVED_CARTS] = current.filter {
                it.endsWith("|$todayKey")
            }.toSet()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns today's epoch day (days since 1970-01-01) — resets at local midnight. */
    private fun todayEpochDay(): Long =
        java.time.LocalDate.now().toEpochDay()
}
