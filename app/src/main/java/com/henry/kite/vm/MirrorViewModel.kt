package com.henry.kite.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.henry.kite.KiteApplication
import com.henry.kite.R
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.session.SessionState
import com.henry.kite.service.MirrorController
import com.henry.kite.settings.MirrorSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Screen state for the discovery list. */
data class DiscoveryUiState(
    val receivers: List<AirPlayReceiver> = emptyList(),
    val pairedIds: Set<String> = emptySet(),
    val isSearching: Boolean = true,
    val discoveryError: String? = null,
)

class MirrorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KiteApplication

    private val pairedIdsState = MutableStateFlow<Set<String>>(emptySet())
    private val discoveryErrorState = MutableStateFlow<String?>(null)

    /** Encoder settings, owned by the settings screen and read here at launch. */
    val settings: StateFlow<MirrorSettings> = app.settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MirrorSettings(),
    )

    /** Set while the receiver is showing a PIN and the dialog is up. */
    private val _pinRequest = MutableStateFlow<AirPlayReceiver?>(null)
    val pinRequest: StateFlow<AirPlayReceiver?> = _pinRequest.asStateFlow()

    /** Set while a receiver is waiting for its AirPlay password (Digest auth). */
    private val _passwordRequest = MutableStateFlow<AirPlayReceiver?>(null)
    val passwordRequest: StateFlow<AirPlayReceiver?> = _passwordRequest.asStateFlow()

    /**
     * Receiver the user tapped, held while the projection consent dialog is up.
     *
     * Lives here, not on the Activity, so rotating the screen while the system
     * dialog is showing does not lose the tap when the result arrives.
     */
    var pendingReceiver: AirPlayReceiver? = null

    val sessionState: StateFlow<SessionState> = MirrorController.state

    val uiState: StateFlow<DiscoveryUiState> = combine(
        app.discovery.receivers().catch { error ->
            discoveryErrorState.value =
                error.message ?: getApplication<Application>().getString(R.string.error_mdns_failed)
            emit(emptyList())
        },
        pairedIdsState,
        discoveryErrorState,
    ) { receivers, paired, error ->
        DiscoveryUiState(
            receivers = receivers,
            pairedIds = paired,
            isSearching = receivers.isEmpty() && error == null,
            discoveryError = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DiscoveryUiState(),
    )

    init {
        refreshPairedIds()
        // The controller asks here when a receiver needs its PIN entered.
        MirrorController.onPinRequired = { receiver -> _pinRequest.value = receiver }
        // ... or its AirPlay password.
        MirrorController.onPasswordRequired = { receiver -> _passwordRequest.value = receiver }
        // The QS-tile picker hands off to this screen mid-session; if a pairing
        // request is already waiting, pick it up so the dialog shows at once.
        when (val state = MirrorController.state.value) {
            is SessionState.AwaitingPin -> _pinRequest.value = state.receiver
            is SessionState.AwaitingPassword -> _passwordRequest.value = state.receiver
            else -> Unit
        }
    }

    fun submitPin(pin: String) {
        _pinRequest.value = null
        MirrorController.providePin(pin)
    }

    fun cancelPin() {
        _pinRequest.value = null
        MirrorController.providePin(null)
    }

    fun submitPassword(password: String) {
        _passwordRequest.value = null
        MirrorController.providePassword(password)
    }

    fun cancelPassword() {
        _passwordRequest.value = null
        MirrorController.providePassword(null)
    }

    fun forget(receiver: AirPlayReceiver) {
        viewModelScope.launch {
            app.credentialStore.forget(receiver.stableId)
            refreshPairedIds()
        }
    }

    fun clearError() = MirrorController.clearError()

    private fun refreshPairedIds() {
        viewModelScope.launch {
            pairedIdsState.value = app.credentialStore.pairedReceiverIds()
        }
    }

    /** Called after a session ends so the "paired" badges stay accurate. */
    fun onSessionEnded() = refreshPairedIds()

    /**
     * Re-reads the stored pairings. The settings screen can forget a receiver
     * while this screen sits in the back stack, so the badges refresh on resume.
     */
    fun refreshPairedReceivers() = refreshPairedIds()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
