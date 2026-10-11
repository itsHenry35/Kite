package com.henry.kite.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.henry.kite.R
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.discovery.PairingPath

/**
 * One receiver in the discovery list.
 *
 * The container morphs on press: corners round out and the card shrinks slightly.
 * Both use a bouncy spring rather than a duration, so an interrupted press
 * retargets from wherever the animation currently is instead of snapping.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReceiverCard(
    receiver: AirPlayReceiver,
    isPaired: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Audio-only cast entry; null means the card has no long-press action. */
    onLongClick: (() -> Unit)? = null,
    /** false drops the leading avatar; the Quick Settings picker keeps rows compact. */
    showAvatar: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "receiverCardScale",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) 28.dp else 20.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "receiverCardCorner",
    )

    // combinedClickable instead of Card(onClick): the click handler owns tap
    // AND long-press on one chain, so a long press cannot also leak a click
    // (with Card(onClick) plus an outer detectTapGestures both fired, and the
    // two consent dialogs cancelled each other out).
    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier
            .fillMaxWidth()
            .scale(pressScale)
            // Clip before the click handler so the ripple follows the card's
            // rounded corners instead of drawing as a rectangle over them.
            .clip(RoundedCornerShape(cornerRadius))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            if (showAvatar) {
                ReceiverAvatar(receiver = receiver, isPaired = isPaired)

                Spacer(Modifier.size(16.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = receiver.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = receiver.subtitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isPaired) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.receiver_paired),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
            }

            Icon(
                imageVector = Icons.Outlined.Cast,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
        }
    }
}

@Composable
private fun ReceiverAvatar(receiver: AirPlayReceiver, isPaired: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isPaired) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = receiverIcon(receiver),
                contentDescription = null,
                tint = if (isPaired) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Best-fit glyph from the advertised model: Macs read as laptops, everything
 *  else that mirrors reads as the generic receiver screen. */
private fun receiverIcon(receiver: AirPlayReceiver): ImageVector = when {
    receiver.isAudioOnly -> Icons.Outlined.Speaker
    receiver.model?.startsWith("Mac") == true -> Icons.Outlined.LaptopMac
    else -> Icons.Outlined.Tv
}

/** Model plus whatever caveat matters most for this receiver. */
@Composable
private fun AirPlayReceiver.subtitle(): String = when {
    isAudioOnly -> stringResource(R.string.receiver_audio_only)
    !supportsMirroring -> stringResource(R.string.receiver_no_mirroring)
    pairingPath == PairingPath.LEGACY -> stringResource(R.string.receiver_legacy_pairing)
    else -> displayModel
}
