package com.henry.kite.service

import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared handle between the UI and [MirrorService].
 *
 * The session outlives any one Activity, so state lives here rather than in a
 * ViewModel. A process-wide object is enough because only one mirroring session
 * can exist at a time.
 */
object MirrorController {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Set by the ViewModel; the service calls it when a receiver wants its PIN. */
    var onPinRequired: ((AirPlayReceiver) -> Unit)? = null

    /** Set by the ViewModel; the service calls it when a receiver wants its password. */
    var onPasswordRequired: ((AirPlayReceiver) -> Unit)? = null

    /** Set by the service; mirrors every published state elsewhere, e.g. the QS tile. */
    var onPublish: ((SessionState) -> Unit)? = null

    private var pendingPin: CompletableDeferred<String?>? = null
    private var pendingPassword: CompletableDeferred<String?>? = null

    internal fun publish(state: SessionState) {
        _state.value = state
        onPublish?.invoke(state)
    }

    /**
     * Suspends until the UI supplies a PIN, or null if the user backs out.
     * Called from the session's pairing step.
     */
    internal suspend fun requestPin(receiver: AirPlayReceiver): String? {
        val deferred = CompletableDeferred<String?>()
        pendingPin = deferred
        onPinRequired?.invoke(receiver)
        return deferred.await().also { pendingPin = null }
    }

    fun providePin(pin: String?) {
        pendingPin?.complete(pin)
    }

    /**
     * Suspends until the UI supplies the receiver's AirPlay password, or null if
     * the user backs out. Called when RTSP Digest auth hits a 401.
     */
    internal suspend fun requestPassword(receiver: AirPlayReceiver): String? {
        val deferred = CompletableDeferred<String?>()
        pendingPassword = deferred
        onPasswordRequired?.invoke(receiver)
        return deferred.await().also { pendingPassword = null }
    }

    fun providePassword(password: String?) {
        pendingPassword?.complete(password)
    }

    fun clearError() {
        if (_state.value is SessionState.Failed) _state.value = SessionState.Idle
    }
}
