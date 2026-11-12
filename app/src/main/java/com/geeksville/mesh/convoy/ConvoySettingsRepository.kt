package com.geeksville.mesh.convoy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.geeksville.mesh.grouptrack.GtSettingsAndroid
import com.grouptrack.shared.settings.GtRideSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/*
 * SWITCH17G2B2-2026-11-12 (2.8 step 17, G2b-2 switch): the ride settings live in the settings socket (GtRideSettings, store
 * "convoy_settings" -- SharedPreferences), certified against 2.7e's DataStore version. Every public name, type and default is 2.7e's,
 * so ConvoyViewModel and the Settings screen do not change. The flows now follow the store: each change updates them at once.
 * ONE-TIME COPY (CODE RULE 3 -- no "done" marker; remove when the release after 2.8a is cut): at start the values 2.7e saved in its
 * DataStore are copied across, but ONLY into keys the new store does not hold yet, so nothing saved since is ever overwritten and a later
 * start copies nothing. The DataStore is only read for that copy (and the TEST-ONLY certRawSnapshot).
 */
private val Context.convoyDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "convoy_settings")

@Singleton
class ConvoySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val rs = GtRideSettings(GtSettingsAndroid(context), GtRideSettings.STORE) { LocalDate.now().toEpochDay() }

    private val _drop = MutableStateFlow(rs.signalDropMinutes())
    private val _lost = MutableStateFlow(rs.signalLostMinutes())
    private val _off = MutableStateFlow(rs.offTrackMiles())
    private val _offSaved = MutableStateFlow(rs.offTrackMilesSaved())   // CODE RULE 1: null = never saved (2.7e offTrackMilesSaved)
    private val _adm = MutableStateFlow(rs.admissionWindowHours())
    private val _removed = MutableStateFlow(rs.removedCartsForToday())

    val signalDropMinutes: Flow<Float> = _drop
    val signalLostMinutes: Flow<Float> = _lost
    val offTrackMiles: Flow<Float> = _off
    val offTrackMilesSaved: Flow<Float?> = _offSaved
    val admissionWindowHours: Flow<Int> = _adm
    val removedCartsForToday: Flow<Map<String, String>> = _removed

    private fun refresh() {
        _drop.value = rs.signalDropMinutes(); _lost.value = rs.signalLostMinutes(); _off.value = rs.offTrackMiles()
        _offSaved.value = rs.offTrackMilesSaved(); _adm.value = rs.admissionWindowHours(); _removed.value = rs.removedCartsForToday()
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val old = context.convoyDataStore.data.first().asMap().mapKeys { it.key.name }
                val n = rs.fillMissing(old)
                android.util.Log.i("ConvoySettings", "SWITCH17G2B2 one-time copy: ${old.size} DataStore value(s), $n copied (the rest were already saved)")
                refresh()
            }.onFailure { android.util.Log.e("ConvoySettings", "SWITCH17G2B2 one-time copy failed: ${it.message}") }
        }
    }

    suspend fun setSignalDropMinutes(value: Float) { rs.setSignalDropMinutes(value); ConvoyConfig.SIGNAL_DROP_MINUTES = value; refresh() }
    suspend fun setSignalLostMinutes(value: Float) { rs.setSignalLostMinutes(value); ConvoyConfig.LOST_MINUTES = value; refresh() }
    suspend fun setOffTrackMiles(value: Float) { rs.setOffTrackMiles(value); ConvoyConfig.OFF_TRACK_MILES = value; refresh() }
    suspend fun setAdmissionWindowHours(value: Int) { rs.setAdmissionWindowHours(value); refresh() }
    suspend fun removeCart(nodeId: String, callsign: String) { rs.removeCart(nodeId, callsign); refresh() }
    suspend fun clearRemovedCarts() { rs.clearRemovedCarts(); refresh() }
    suspend fun reinstateCart(nodeId: String) { rs.reinstateCart(nodeId); refresh() }
    suspend fun purgeStaleRemovedCarts() { rs.purgeStaleRemovedCarts(); refresh() }

    // STEP17G2B2-2026-11-12 TEST-ONLY (CODE RULE 3): the rider's DataStore values, READ ONLY, for the G2b-2 cert (GtCert17b2) -- kept until GtCertRun goes
    suspend fun certRawSnapshot(): Map<String, Any?> = context.convoyDataStore.data.first().asMap().mapKeys { it.key.name }
}
