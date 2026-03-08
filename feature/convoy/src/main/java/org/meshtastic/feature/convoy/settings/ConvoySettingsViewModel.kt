package org.meshtastic.feature.convoy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UI State ──────────────────────────────────────────────────────────────────

data class ConvoySettingsUiState(
    // Alert thresholds
    val signalDropDbm:        Float = ConvoySettingsRepository.DEFAULT_SIGNAL_DROP_DBM,
    val signalLostMinutes:    Int   = ConvoySettingsRepository.DEFAULT_SIGNAL_LOST_MINUTES,
    val offTrackMeters:       Int   = ConvoySettingsRepository.DEFAULT_OFF_TRACK_METERS,

    // Node filter
    val admissionWindowHours: Int   = ConvoySettingsRepository.DEFAULT_ADMISSION_WINDOW_HOURS,

    // Removed carts
    val removedCartIds:       Set<String> = emptySet(),

    // Loading state
    val isLoading:            Boolean = true,

    // Snackbar feedback
    val userMessage:          String? = null
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class ConvoySettingsViewModel @Inject constructor(
    private val repository: ConvoySettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConvoySettingsUiState())
    val uiState: StateFlow<ConvoySettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Purge removed carts from previous days on every launch
            repository.purgeStaleRemovedCarts()
        }
        observeSettings()
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                repository.signalDropDbm,
                repository.signalLostMinutes,
                repository.offTrackMeters,
                repository.admissionWindowHours,
                repository.removedCartIdsForToday
            ) { signalDrop, signalLost, offTrack, admissionWindow, removedCarts ->
                ConvoySettingsUiState(
                    signalDropDbm        = signalDrop,
                    signalLostMinutes    = signalLost,
                    offTrackMeters       = offTrack,
                    admissionWindowHours = admissionWindow,
                    removedCartIds       = removedCarts,
                    isLoading            = false
                )
            }.collect { state ->
                _uiState.update { state }
            }
        }
    }

    // ── Alert Threshold Actions ───────────────────────────────────────────────

    fun onSignalDropDbmChanged(value: Float) {
        viewModelScope.launch { repository.setSignalDropDbm(value) }
    }

    fun onSignalLostMinutesChanged(value: Int) {
        viewModelScope.launch { repository.setSignalLostMinutes(value) }
    }

    fun onOffTrackMetersChanged(value: Int) {
        viewModelScope.launch { repository.setOffTrackMeters(value) }
    }

    // ── Node Filter Actions ───────────────────────────────────────────────────

    fun onAdmissionWindowHoursChanged(value: Int) {
        viewModelScope.launch { repository.setAdmissionWindowHours(value) }
    }

    // ── Removed Cart Actions ──────────────────────────────────────────────────

    fun onReinstateCart(nodeId: String, callsign: String) {
        viewModelScope.launch {
            repository.reinstateCart(nodeId)
            _uiState.update { it.copy(userMessage = "$callsign reinstated") }
        }
    }

    fun onUserMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
