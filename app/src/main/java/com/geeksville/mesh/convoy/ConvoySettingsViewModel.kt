package com.geeksville.mesh.convoy

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

data class ConvoySettingsUiState(
    val signalDropMinutes:    Float              = 2f,
    val signalLostMinutes:    Float              = 10f,
    val offTrackMiles:        Float              = 0.5f,
    val admissionWindowHours: Int                = 1,
    // Map of nodeId -> callsign for removed carts today
    val removedCarts:         Map<String, String> = emptyMap(),
    val userMessage:          String?             = null
)

@HiltViewModel
class ConvoySettingsViewModel @Inject constructor(
    private val repository: ConvoySettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConvoySettingsUiState())
    val uiState: StateFlow<ConvoySettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.purgeStaleRemovedCarts() }
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                repository.signalDropMinutes,
                repository.signalLostMinutes,
                repository.offTrackMiles,
                repository.admissionWindowHours,
                repository.removedCartsForToday
            ) { drop, lost, offTrack, window, removed ->
                ConvoySettingsUiState(
                    signalDropMinutes    = drop,
                    signalLostMinutes    = lost,
                    offTrackMiles        = offTrack,
                    admissionWindowHours = window,
                    removedCarts         = removed
                )
            }.collect { _uiState.value = it }
        }
    }

    fun onSignalDropChanged(value: Float) {
        viewModelScope.launch { repository.setSignalDropMinutes(value) }
    }

    fun onSignalLostChanged(value: Float) {
        viewModelScope.launch { repository.setSignalLostMinutes(value) }
    }

    fun onOffTrackChanged(value: Float) {
        viewModelScope.launch { repository.setOffTrackMiles(value) }
    }

    fun onAdmissionWindowChanged(value: Int) {
        viewModelScope.launch { repository.setAdmissionWindowHours(value) }
    }

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
