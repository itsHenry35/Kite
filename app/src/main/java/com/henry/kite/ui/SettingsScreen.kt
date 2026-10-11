package com.henry.kite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.henry.kite.R
import com.henry.kite.settings.FrameRate
import com.henry.kite.settings.MirrorSettings
import com.henry.kite.settings.Quality
import com.henry.kite.ui.components.ChoiceOption
import com.henry.kite.ui.components.SettingsRow
import com.henry.kite.ui.components.SettingsSection
import com.henry.kite.ui.components.SingleChoiceDialog
import kotlin.math.roundToInt

/** Settings, as its own screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: MirrorSettings,
    isBatteryExempt: Boolean,
    onSelectQuality: (Quality) -> Unit,
    onSelectFrameRate: (FrameRate) -> Unit,
    onSelectBitRate: (Int) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onShareLogs: () -> Unit,
    onOpenSavedReceivers: () -> Unit,
    onOpenGitHub: () -> Unit,
    versionLabel: String,
    onBack: () -> Unit,
) {
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)
    var showQualityPicker by remember { mutableStateOf(false) }
    var showFrameRatePicker by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        // M3 center-aligned content column: full width on phones, capped and
        // centered once the window is medium or wider (landscape, tablets).
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 32.dp,
                        ),
                    ),
            ) {
                SettingsSection(title = stringResource(R.string.settings_section_video)) {
                    // Resolution labels are too long for a segmented button, which the
                    // spec says must not truncate — so this row opens a picker instead.
                    SettingsRow(
                        title = stringResource(R.string.quality_title),
                        summary = settings.quality.label(),
                        onClick = { showQualityPicker = true },
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_frame_rate),
                        summary = "${settings.frameRate.fps} fps",
                        onClick = { showFrameRatePicker = true },
                    )
                    BitRateRow(settings.bitRateMbps, onSelectBitRate)
                }

                SettingsSection(title = stringResource(R.string.settings_section_system)) {
                    // Tapping an already-exempt app would send the user to a dialog
                    // with nothing to do, so the row goes inert once granted.
                    SettingsRow(
                        title = stringResource(R.string.settings_battery),
                        summary = stringResource(
                            if (isBatteryExempt) {
                                R.string.settings_battery_exempt
                            } else {
                                R.string.settings_battery_restricted
                            },
                        ),
                        supporting = stringResource(R.string.settings_battery_summary),
                        onClick = if (isBatteryExempt) null else onRequestBatteryExemption,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_logs),
                        supporting = stringResource(R.string.settings_logs_summary),
                        onClick = onShareLogs,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_saved_receivers),
                        onClick = onOpenSavedReceivers,
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_about_version),
                        summary = versionLabel,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_about_github),
                        summary = GITHUB_URL,
                        onClick = onOpenGitHub,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_licenses),
                        onClick = { showLicenses = true },
                    )
                }
            }
        }
    }

    if (showQualityPicker) {
        val options = Quality.entries.map { ChoiceOption(it, it.label()) }
        SingleChoiceDialog(
            title = stringResource(R.string.quality_title),
            options = options,
            selected = settings.quality,
            onSelect = onSelectQuality,
            onDismiss = { showQualityPicker = false },
        )
    }

    if (showFrameRatePicker) {
        val options = FrameRate.entries.map { ChoiceOption(it, "${it.fps} fps") }
        SingleChoiceDialog(
            title = stringResource(R.string.settings_frame_rate),
            options = options,
            selected = settings.frameRate,
            onSelect = onSelectFrameRate,
            onDismiss = { showFrameRatePicker = false },
        )
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}

/**
 * Bit rate, split into the two choices it actually is: derive a sensible value
 * from the resolution (auto), or set one manually. The slider appears only for
 * the manual choice — folding "auto" into the leftmost slider stop made the
 * low end of the range hard to hit and the auto state invisible.
 */
@Composable
private fun BitRateRow(selectedMbps: Int, onSelect: (Int) -> Unit) {
    val min = MirrorSettings.BIT_RATE_MIN_MBPS
    val max = MirrorSettings.BIT_RATE_MAX_MBPS
    val isAuto = selectedMbps == MirrorSettings.BIT_RATE_AUTO

    SettingsRow(
        title = stringResource(R.string.settings_bit_rate),
        summary = if (isAuto) {
            stringResource(R.string.settings_bit_rate_auto)
        } else {
            stringResource(R.string.settings_bit_rate_value, selectedMbps)
        },
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = isAuto,
                onClick = { onSelect(MirrorSettings.BIT_RATE_AUTO) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) {
                Text(stringResource(R.string.settings_bit_rate_auto), maxLines = 1)
            }
            SegmentedButton(
                selected = !isAuto,
                onClick = { onSelect(MirrorSettings.BIT_RATE_MANUAL_DEFAULT_MBPS) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) {
                Text(stringResource(R.string.settings_bit_rate_manual), maxLines = 1)
            }
        }
        AnimatedVisibility(visible = !isAuto) {
            Slider(
                value = selectedMbps.coerceIn(min, max).toFloat(),
                onValueChange = { raw ->
                    onSelect(raw.roundToInt().coerceIn(min, max))
                },
                valueRange = min.toFloat()..max.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Quality.label(): String = stringResource(
    when (this) {
        Quality.NATIVE -> R.string.quality_native
        Quality.P1080 -> R.string.quality_1080p
        Quality.P720 -> R.string.quality_720p
        Quality.P540 -> R.string.quality_540p
    },
)

/** The project repository, opened from the settings About section. */
const val GITHUB_URL = "https://github.com/itsHenry35/Kite"
