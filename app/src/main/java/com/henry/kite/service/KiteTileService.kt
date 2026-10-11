package com.henry.kite.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.henry.kite.R
import com.henry.kite.TilePickerActivity
import com.henry.kite.airplay.session.activeReceiver

/**
 * The Quick Settings tile: pull down the shade, tap "Screen cast".
 *
 * A tap cannot start a cast on its own — the projection consent dialog has to
 * come from an Activity — so a tap while idle opens the discovery screen.
 * While a session is up the tile lights up and tapping it stops the cast,
 * which is quicker than reaching the notification from inside another app.
 */
class KiteTileService : TileService() {

    override fun onStartListening() {
        applyState()
    }

    override fun onClick() {
        if (MirrorController.state.value.activeReceiver != null) {
            // The cast runs as a foreground service and a tile tap grants the
            // temporary priority needed to deliver a command to it.
            val stop = Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP)
            runCatching { startService(stop) }
                .onFailure { Log.w(TAG, "Could not deliver stop action", it) }
        } else {
            openDiscovery()
        }
        applyState()
    }

    private fun openDiscovery() {
        val intent = Intent(this, TilePickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            // The PendingIntent overload only exists on API 34+.
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun applyState() {
        val tile = qsTile ?: return
        val state = MirrorController.state.value
        val receiver = state.activeReceiver
        if (receiver != null) {
            tile.state = Tile.STATE_ACTIVE
            tile.subtitle = getString(R.string.notification_title, receiver.name)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = getString(R.string.tile_subtitle_idle)
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "KiteTileService"

        /** Asks the system to rebind the tile so it re-reads [MirrorController.state]. */
        fun requestRefresh(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, KiteTileService::class.java))
        }
    }
}
