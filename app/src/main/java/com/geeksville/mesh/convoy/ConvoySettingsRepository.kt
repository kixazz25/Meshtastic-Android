package com.geeksville.mesh.convoy

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
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.convoyDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "convoy_settings")

@Singleton
class ConvoySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_SIGNAL_DROP_MINUTES    = floatPreferencesKey("signal_drop_minutes")
        val KEY_SIGNAL_LOST_MINUTES    = floatPreferencesKey("signal_lost_minutes")
        val KEY_OFF_TRACK_MILES        = floatPreferencesKey("off_track_miles")
        val KEY_ADMISSION_WINDOW_HOURS = intPreferencesKey("admission_window_hours")
        // Format: "nodeId|callsign|epochDay"
        val KEY_REMOVED_CARTS          = stringSetPreferencesKey("removed_carts")
        val KEY_LEAD_LOCK_FEET         = floatPreferencesKey("lead_lock_feet")
    }

    // ── Flows ─────────────────────────────────────────────────────────────

    val signalDropMinutes: Flow<Float> = context.convoyDataStore.data
        .map { it[KEY_SIGNAL_DROP_MINUTES] ?: 2f }

    val signalLostMinutes: Flow<Float> = context.convoyDataStore.data
        .map { it[KEY_SIGNAL_LOST_MINUTES] ?: 10f }

    val offTrackMiles: Flow<Float> = context.convoyDataStore.data
        .map { it[KEY_OFF_TRACK_MILES] ?: 0.5f }

    val admissionWindowHours: Flow<Int> = context.convoyDataStore.data
        .map { it[KEY_ADMISSION_WINDOW_HOURS] ?: 1 }
    // Lead lock distance — 300 to 1500 feet. Default 500 feet (~17 sec at 20mph)
    val leadLockFeet: Flow<Float> = context.convoyDataStore.data
        .map { it[KEY_LEAD_LOCK_FEET] ?: 330f }

    /** Map of nodeId -> callsign for carts removed today only */
    val removedCartsForToday: Flow<Map<String, String>> = context.convoyDataStore.data
        .map { prefs ->
            val today = LocalDate.now().toEpochDay().toString()
            (prefs[KEY_REMOVED_CARTS] ?: emptySet())
                .filter { it.endsWith("|$today") }
                .associate { entry ->
                    val parts = entry.split("|")
                    parts[0] to parts[1]   // nodeId to callsign
                }
        }

    // ── Writers ───────────────────────────────────────────────────────────

    suspend fun setSignalDropMinutes(value: Float) {
        context.convoyDataStore.edit { it[KEY_SIGNAL_DROP_MINUTES] = value }
        ConvoyConfig.SIGNAL_DROP_MINUTES = value
    }

    suspend fun setSignalLostMinutes(value: Float) {
        context.convoyDataStore.edit { it[KEY_SIGNAL_LOST_MINUTES] = value }
        ConvoyConfig.LOST_MINUTES = value
    }

    suspend fun setOffTrackMiles(value: Float) {
        context.convoyDataStore.edit { it[KEY_OFF_TRACK_MILES] = value }
        ConvoyConfig.OFF_TRACK_MILES = value
    }

    suspend fun setAdmissionWindowHours(value: Int) {
        context.convoyDataStore.edit { it[KEY_ADMISSION_WINDOW_HOURS] = value }
    }
    suspend fun setLeadLockFeet(value: Float) {
        context.convoyDataStore.edit { it[KEY_LEAD_LOCK_FEET] = value }
    }

    /** Remove a cart for today — stored with callsign for display in settings */
    suspend fun removeCart(nodeId: String, callsign: String) {
        val entry = "$nodeId|$callsign|${LocalDate.now().toEpochDay()}"
        context.convoyDataStore.edit { prefs ->
            val current = prefs[KEY_REMOVED_CARTS] ?: emptySet()
            prefs[KEY_REMOVED_CARTS] = current + entry
        }
    }

    /** Reinstate a removed cart */
    suspend fun reinstateCart(nodeId: String) {
        val today = LocalDate.now().toEpochDay().toString()
        context.convoyDataStore.edit { prefs ->
            val current = prefs[KEY_REMOVED_CARTS] ?: emptySet()
            prefs[KEY_REMOVED_CARTS] = current.filterNot {
                it.startsWith("$nodeId|") && it.endsWith("|$today")
            }.toSet()
        }
    }

    /** Remove entries from previous days — call on app start */
    suspend fun purgeStaleRemovedCarts() {
        val today = LocalDate.now().toEpochDay().toString()
        context.convoyDataStore.edit { prefs ->
            val current = prefs[KEY_REMOVED_CARTS] ?: emptySet()
            prefs[KEY_REMOVED_CARTS] = current.filter {
                it.endsWith("|$today")
            }.toSet()
        }
    }
}
