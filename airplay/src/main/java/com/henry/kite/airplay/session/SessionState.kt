package com.henry.kite.airplay.session

import com.henry.kite.airplay.discovery.AirPlayReceiver

/** What the session layer is doing, surfaced to the UI. */
sealed interface SessionState {
    data object Idle : SessionState

    data class Connecting(val receiver: AirPlayReceiver) : SessionState

    /**
     * Waiting for the PIN the receiver just put on screen. The UI shows its entry
     * dialog here and calls back with the digits.
     */
    data class AwaitingPin(val receiver: AirPlayReceiver) : SessionState

    /**
     * The receiver wants its AirPlay password (RTSP Digest). The UI shows a
     * password dialog; submitting retries the RTSP request that was challenged.
     */
    data class AwaitingPassword(val receiver: AirPlayReceiver) : SessionState

    data class Pairing(val receiver: AirPlayReceiver) : SessionState

    data class SettingUp(val receiver: AirPlayReceiver) : SessionState

    data class Mirroring(
        val receiver: AirPlayReceiver,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        /** Current send rate in kilobits per second, averaged over the last tick. */
        val kilobitsPerSecond: Long = 0,
    ) : SessionState

    /** Audio-only RAOP session (AirPlay 1 devices) — no video, no dimensions. */
    data class StreamingAudio(
        val receiver: AirPlayReceiver,
        /** Current send rate in kilobits per second, averaged over the last tick. */
        val kilobitsPerSecond: Long = 0,
    ) : SessionState

    data class Failed(
        val receiver: AirPlayReceiver?,
        val reason: String,
        val cause: Throwable? = null,
        /** True when stored credentials were rejected and re-pairing will fix it. */
        val requiresRepairing: Boolean = false,
    ) : SessionState
}

/** The receiver this state concerns, or null when nothing is running. */
val SessionState.activeReceiver: AirPlayReceiver?
    get() = when (this) {
        is SessionState.Connecting -> receiver
        is SessionState.AwaitingPin -> receiver
        is SessionState.AwaitingPassword -> receiver
        is SessionState.Pairing -> receiver
        is SessionState.SettingUp -> receiver
        is SessionState.Mirroring -> receiver
        is SessionState.StreamingAudio -> receiver
        is SessionState.Failed -> null
        SessionState.Idle -> null
    }
