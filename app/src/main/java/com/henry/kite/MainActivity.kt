package com.henry.kite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.session.SessionState
import com.henry.kite.service.MirrorService
import com.henry.kite.service.startCast
import com.henry.kite.ui.DiscoveryScreen
import com.henry.kite.ui.components.PasswordDialog
import com.henry.kite.ui.components.PinDialog
import com.henry.kite.ui.theme.KiteTheme
import com.henry.kite.vm.MirrorViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MirrorViewModel by viewModels()

    private var pendingAudioOnly = false
    private var pendingVideoOnly = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val receiver = viewModel.pendingReceiver
        viewModel.pendingReceiver = null
        val audioOnly = pendingAudioOnly
        val videoOnly = pendingVideoOnly
        pendingAudioOnly = false
        pendingVideoOnly = false
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null || receiver == null) {
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            startCast(
                context = this@MainActivity,
                receiver = receiver,
                resultCode = result.resultCode,
                resultData = data,
                audioOnly = audioOnly,
                videoOnly = videoOnly,
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Discovery and notifications degrade rather than fail; no action needed. */ }

    // One-shot wiring of the whole screen: permission launchers, discovery,
    // dialogs and the cast/pairing state machine all attach here.
    @Suppress("LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()

        setContent {
            @OptIn(ExperimentalMaterial3Api::class)
            KiteTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
                val pinRequest by viewModel.pinRequest.collectAsStateWithLifecycle()
                val passwordRequest by viewModel.passwordRequest.collectAsStateWithLifecycle()

                // Settings → Saved receivers can forget a receiver while this
                // screen sits in the back stack; refresh the badges on return.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshPairedReceivers()
                }

                var audioOnlyTarget by remember { mutableStateOf<AirPlayReceiver?>(null) }

                DiscoveryScreen(
                    uiState = uiState,
                    sessionState = sessionState,
                    onStartMirroring = ::requestProjection,
                    onReceiverLongPress = { audioOnlyTarget = it },
                    onStopMirroring = {
                        MirrorService.stop(this)
                        viewModel.onSessionEnded()
                    },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                )

                // Long-press presents the choices instead of committing to one:
                // screen with sound, screen without sound, or just the sound.
                // An AlertDialog, same as every other dialog in this app — its
                // surface color matches by construction.
                audioOnlyTarget?.let { receiver ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { audioOnlyTarget = null },
                        title = { Text(receiver.name) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        audioOnlyTarget = null
                                        requestProjection(receiver)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        stringResource(R.string.receiver_action_mirror),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        audioOnlyTarget = null
                                        requestProjection(receiver, videoOnly = true)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        stringResource(R.string.receiver_action_video_only),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        audioOnlyTarget = null
                                        requestProjection(receiver, audioOnly = true)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        stringResource(R.string.receiver_action_audio_only),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {},
                    )
                }

                pinRequest?.let { receiver ->
                    PinDialog(
                        receiverName = receiver.name,
                        onSubmit = viewModel::submitPin,
                        onDismiss = viewModel::cancelPin,
                    )
                }

                passwordRequest?.let { receiver ->
                    PasswordDialog(
                        receiverName = receiver.name,
                        onSubmit = viewModel::submitPassword,
                        onDismiss = viewModel::cancelPassword,
                    )
                }
            }
        }
    }

    /**
     * Asks for screen capture consent. Android requires a fresh grant every time;
     * the token cannot be cached across sessions.
     */
    private fun requestProjection(receiver: AirPlayReceiver, audioOnly: Boolean = false, videoOnly: Boolean = false) {
        if (viewModel.sessionState.value !is SessionState.Idle &&
            viewModel.sessionState.value !is SessionState.Failed
        ) {
            return // A session is already running.
        }
        viewModel.pendingReceiver = receiver
        pendingAudioOnly = audioOnly
        pendingVideoOnly = videoOnly
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                // Without this, mDNS returns nothing on Android 13+.
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // BLE beacon discovery (mDNS-blocked networks).
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                // Pre-31 BLE scan results are gated on location.
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // Playback capture rides on the projection grant but is still an
            // AudioRecord, so without this the audio stream stays silent.
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
