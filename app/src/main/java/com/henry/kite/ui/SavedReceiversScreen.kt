package com.henry.kite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.henry.kite.R

/**
 * Settings → Saved receivers: every receiver with stored pairing material, with
 * a per-entry forget action behind a confirmation. The rows reuse the receiver
 * card's visual language — rounded surface, tonal avatar — so the screen reads
 * as part of the same app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedReceiversScreen(receivers: List<SavedReceiver>, onForget: (SavedReceiver) -> Unit, onBack: () -> Unit) {
    var pendingForget by remember { mutableStateOf<SavedReceiver?>(null) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_saved_receivers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (receivers.isEmpty()) {
                EmptyState(topPadding = padding.calculateTopPadding())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
                ) {
                    items(receivers, key = { it.stableId }) { receiver ->
                        SavedReceiverCard(receiver = receiver, onDelete = { pendingForget = receiver })
                    }
                }
            }
        }
    }

    pendingForget?.let { receiver ->
        val displayName = receiver.name ?: stringResource(R.string.saved_receivers_unknown)
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text(stringResource(R.string.saved_receivers_forget_title, displayName)) },
            text = { Text(stringResource(R.string.saved_receivers_forget_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingForget = null
                        onForget(receiver)
                    },
                ) {
                    Text(stringResource(R.string.action_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingForget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** One stored pairing, drawn like the receiver cards on the main screen. */
@Composable
private fun SavedReceiverCard(receiver: SavedReceiver, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = iconFor(receiver.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.size(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = receiver.name ?: stringResource(R.string.saved_receivers_unknown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The stored id is what disambiguates two receivers that carry
                // the same display name.
                Text(
                    text = receiver.stableId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_forget),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The same name-based hint the receiver cards use, minus the model field. */
private fun iconFor(name: String?): ImageVector = if (name?.contains("Mac", ignoreCase = true) == true) {
    Icons.Outlined.LaptopMac
} else {
    Icons.Outlined.Tv
}

@Composable
private fun EmptyState(topPadding: androidx.compose.ui.unit.Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding + 64.dp, start = 24.dp, end = 24.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Cast,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.height(48.dp),
        )
        Text(
            text = stringResource(R.string.saved_receivers_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
