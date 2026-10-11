package com.henry.kite.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.henry.kite.R
import com.henry.kite.airplay.session.SessionState

/**
 * Session status strip above the receiver list.
 *
 * Contents cross-fade through [AnimatedContent] so a state change reads as one
 * surface updating rather than a row being swapped out.
 */
@Composable
fun StatusPanel(state: SessionState, modifier: Modifier = Modifier) {
    val isError = state is SessionState.Failed

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(spring(stiffness = Spring.StiffnessMedium)) togetherWith
                    fadeOut(tween(90))
            },
            label = "statusPanel",
        ) { current ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                StatusLeading(current)
                Spacer(Modifier.size(16.dp))
                StatusText(current, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusLeading(state: SessionState) {
    when (state) {
        is SessionState.Mirroring, is SessionState.StreamingAudio -> PulsingDot()

        is SessionState.Failed -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(24.dp),
        )

        else -> CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun StatusText(state: SessionState, modifier: Modifier = Modifier) {
    val onContainer = if (state is SessionState.Failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val title = when (state) {
            is SessionState.Idle -> ""
            is SessionState.Connecting -> stringResource(R.string.state_connecting)
            is SessionState.AwaitingPin -> stringResource(R.string.state_pairing)
            is SessionState.AwaitingPassword -> stringResource(R.string.state_password_required)
            is SessionState.Pairing -> stringResource(R.string.state_pairing)
            is SessionState.SettingUp -> stringResource(R.string.state_setting_up)
            is SessionState.Mirroring -> stringResource(R.string.state_mirroring)
            is SessionState.StreamingAudio -> stringResource(R.string.state_streaming_audio)
            is SessionState.Failed -> stringResource(R.string.error_title)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = onContainer,
        )

        val detail = when (state) {
            is SessionState.Connecting -> state.receiver.name

            is SessionState.AwaitingPin -> state.receiver.name

            is SessionState.AwaitingPassword -> state.receiver.name

            is SessionState.Pairing -> state.receiver.name

            is SessionState.SettingUp -> state.receiver.name

            is SessionState.Mirroring -> stringResource(
                R.string.mirroring_resolution,
                state.width,
                state.height,
                state.frameRate,
            ) + "  ·  " + formatRate(state.kilobitsPerSecond)

            is SessionState.StreamingAudio ->
                state.receiver.name +
                    "  ·  " + formatRate(state.kilobitsPerSecond)

            is SessionState.Failed -> if (state.requiresRepairing) {
                stringResource(R.string.error_repair_needed)
            } else {
                state.reason
            }

            SessionState.Idle -> ""
        }
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.78f),
            )
        }
    }
}

/** kbps under 1 Mbps, Mbps above it, so the number stays short either way. */
@Composable
private fun formatRate(kilobitsPerSecond: Long): String = if (kilobitsPerSecond < 1_000) {
    stringResource(R.string.mirroring_rate_kbps, kilobitsPerSecond)
} else {
    stringResource(R.string.mirroring_rate_mbps, kilobitsPerSecond / 1000.0f)
}

/** Breathing dot: the one place a looping animation earns its keep. */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Box(
        Modifier
            .size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .alpha(alpha)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}
