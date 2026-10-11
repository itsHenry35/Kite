package com.henry.kite.service

import android.content.Context
import android.content.Intent
import com.henry.kite.KiteApplication
import com.henry.kite.airplay.discovery.AirPlayReceiver

/**
 * Starts a cast from an approved projection-consent result. Both the main
 * screen and the Quick Settings picker call this, so the two entry points
 * cannot drift in which settings or cast mode they use.
 *
 * The stored settings are read here rather than kept as a StateFlow: neither
 * caller collects that flow, so a cached `value` would stay at defaults and
 * silently ignore whatever the settings screen just changed.
 */
suspend fun startCast(
    context: Context,
    receiver: AirPlayReceiver,
    resultCode: Int,
    resultData: Intent,
    audioOnly: Boolean = false,
    videoOnly: Boolean = false,
) {
    val settings = (context.applicationContext as KiteApplication).settingsRepository.current()
    MirrorService.start(
        context = context,
        receiver = receiver,
        resultCode = resultCode,
        resultData = resultData,
        maxLongEdge = settings.quality.maxLongEdge,
        frameRate = settings.frameRate.fps,
        bitRate = settings.bitRateBps(),
        audioOnly = audioOnly,
        videoOnly = videoOnly,
    )
}
