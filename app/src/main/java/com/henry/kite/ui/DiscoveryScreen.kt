package com.henry.kite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.henry.kite.R
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.session.SessionState
import com.henry.kite.airplay.session.activeReceiver
import com.henry.kite.ui.components.StatusPanel
import com.henry.kite.ui.components.receiverItems
import com.henry.kite.vm.DiscoveryUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    uiState: DiscoveryUiState,
    sessionState: SessionState,
    onStartMirroring: (AirPlayReceiver) -> Unit,
    /** Long-press on a receiver opens its action menu (mirror / audio only). */
    onReceiverLongPress: (AirPlayReceiver) -> Unit,
    onStopMirroring: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    // Any non-idle, non-failed state means a session is mid-flight.
    val isBusy = sessionState !is SessionState.Idle && sessionState !is SessionState.Failed

    // The receiver of a running session sorts to the top: returning to the app
    // mid-cast, the thing the user is connected to is the first thing they see.
    val activeReceiverId = sessionState.activeReceiver?.stableId
    val orderedReceivers = remember(uiState.receivers, activeReceiverId) {
        if (activeReceiverId == null) {
            uiState.receivers
        } else {
            uiState.receivers.sortedByDescending { it.stableId == activeReceiverId }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.discovery_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_open),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            SessionControls(
                sessionState = sessionState,
                onStopMirroring = onStopMirroring,
            )
        },
    ) { padding ->
        // Same M3 center-aligned pattern as the settings screen: capped and
        // centered on medium+ windows, untouched on phones.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
            ) {
                item {
                    AnimatedVisibility(
                        visible = sessionState !is SessionState.Idle,
                        enter = fadeIn() + expandVertically(
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            StatusPanel(sessionState)
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.discovery_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }

                receiverItems(
                    receivers = orderedReceivers,
                    pairedIds = uiState.pairedIds,
                    enabled = !isBusy,
                    onSelect = onStartMirroring,
                    onLongPress = onReceiverLongPress,
                )

                if (uiState.receivers.isEmpty()) {
                    item {
                        EmptyState(
                            isSearching = uiState.isSearching,
                            error = uiState.discoveryError,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(isSearching: Boolean, error: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.WifiFind,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.height(48.dp),
        )
        Text(
            text = when {
                error != null -> error
                isSearching -> stringResource(R.string.discovery_searching)
                else -> stringResource(R.string.discovery_empty_title)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!isSearching || error != null) {
            Text(
                text = stringResource(R.string.discovery_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Only the stop button now; encoder options live in [SettingsScreen]. */
@Composable
private fun SessionControls(sessionState: SessionState, onStopMirroring: () -> Unit) {
    val isMirroring = sessionState is SessionState.Mirroring

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        AnimatedVisibility(
            visible = isMirroring,
            enter = fadeIn() + expandVertically(
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Button(
                onClick = onStopMirroring,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = stringResource(R.string.action_stop),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
