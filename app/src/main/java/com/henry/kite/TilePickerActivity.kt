package com.henry.kite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.session.SessionState
import com.henry.kite.service.MirrorController
import com.henry.kite.service.startCast
import com.henry.kite.ui.components.receiverItems
import com.henry.kite.ui.theme.KiteTheme
import kotlinx.coroutines.launch

/**
 * The dialog-style receiver picker the Quick Settings tile opens.
 *
 * A floating [AlertDialog] over whatever the user was doing: pick a receiver,
 * answer the projection consent dialog, done — the main app never comes up.
 * The receiver rows are [receiverItems], the same composable the main screen
 * renders, and casting goes through the same [startCast] path, so the two
 * entry points stay behaviourally identical.
 *
 * Pairing challenges are handed to [MainActivity], which owns the PIN and
 * password dialogs; once audio or video flows the picker closes itself and
 * the cast continues in the foreground service.
 */
class TilePickerActivity : ComponentActivity() {

    /** The receiver awaiting the projection consent answer. Main thread only. */
    private var pendingReceiver: AirPlayReceiver? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val receiver = pendingReceiver
        pendingReceiver = null
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null || receiver == null) {
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            startCast(this@TilePickerActivity, receiver, result.resultCode, data)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* The cast degrades to video-only without the mic; nothing else to do. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent { KiteTheme { PickerDialog() } }
    }

    /**
     * The mic permission is what lets the service claim the microphone
     * foreground-service type later, so it has to be asked before the cast
     * starts; from the tile this picker is the only chance.
     */
    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN) // BLE beacon discovery
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun launchConsent(receiver: AirPlayReceiver) {
        pendingReceiver = receiver
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    @Composable
    private fun PickerDialog() {
        val app = application as KiteApplication
        val receivers by app.discovery.receivers()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val session by MirrorController.state.collectAsStateWithLifecycle()
        // Same badges as the main screen; pairing hands off to MainActivity and
        // closes this dialog, so a one-shot load covers its whole lifetime.
        val pairedIds by produceState(initialValue = emptySet<String>()) {
            value = app.credentialStore.pairedReceiverIds()
        }

        // Hand pairing challenges to the main app; end the dialog once media flows.
        when (val state = session) {
            is SessionState.AwaitingPin, is SessionState.AwaitingPassword -> {
                LaunchedEffect(state) {
                    startActivity(
                        Intent(this@TilePickerActivity, MainActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            ),
                    )
                    finish()
                }
            }

            is SessionState.Mirroring, is SessionState.StreamingAudio -> {
                LaunchedEffect(state) { finish() }
            }

            else -> Unit
        }

        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text(stringResource(R.string.discovery_title)) },
            text = { PickerContent(receivers, pairedIds, session) },
            confirmButton = {
                TextButton(onClick = { finish() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    @Composable
    private fun PickerContent(receivers: List<AirPlayReceiver>, pairedIds: Set<String>, session: SessionState) {
        when {
            session is SessionState.Failed -> Text(
                text = session.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            session !is SessionState.Idle -> BusyRow(stringResource(R.string.tile_pick_connecting))

            receivers.isEmpty() -> BusyRow(stringResource(R.string.discovery_searching))

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                receiverItems(
                    receivers = receivers,
                    pairedIds = pairedIds,
                    enabled = true,
                    onSelect = { launchConsent(it) },
                    showAvatar = false,
                )
            }
        }
    }

    @Composable
    private fun BusyRow(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
