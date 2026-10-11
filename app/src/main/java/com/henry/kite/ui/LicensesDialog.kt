package com.henry.kite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.henry.kite.R

/** One attribution: project, its license, and the project's page. */
private class LicenseEntry(val name: String, val license: String, val url: String)

/** Flat by design: the libraries that ship in the APK, the code ported into it,
 *  and the community projects the protocol work stands on — all in one list,
 *  every entry with its license and a link. */
private val LICENSES = listOf(
    LicenseEntry(
        name = "AndroidX & Jetpack Compose",
        license = "Apache-2.0",
        url = "https://developer.android.com/jetpack/androidx",
    ),
    LicenseEntry(
        name = "Kotlin & kotlinx.coroutines",
        license = "Apache-2.0",
        url = "https://kotlinlang.org",
    ),
    LicenseEntry(
        name = "Material Design 3",
        license = "Apache-2.0",
        url = "https://m3.material.io",
    ),
    LicenseEntry(
        name = "Bouncy Castle",
        license = "MIT",
        url = "https://www.bouncycastle.org",
    ),
    LicenseEntry(
        name = "JmDNS",
        license = "Apache-2.0",
        url = "https://github.com/jmdns/jmdns",
    ),
    LicenseEntry(
        name = "doubletake",
        license = "LGPL-3.0",
        url = "https://github.com/omarroth/doubletake",
    ),
    LicenseEntry(
        name = "UxPlay",
        license = "GPL-3.0",
        url = "https://github.com/FDH2/UxPlay",
    ),
    LicenseEntry(
        name = "pyatv",
        license = "MIT",
        url = "https://github.com/postlund/pyatv",
    ),
    LicenseEntry(
        name = "shairport-sync",
        license = "MIT",
        url = "https://github.com/mikebrady/shairport-sync",
    ),
)

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_licenses)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.licenses_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (entry in LICENSES) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "${entry.name} — ${entry.license}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = linkText(entry.url),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

/** The project URL as a link; Text opens LinkAnnotation.Url natively, so the
 *  entry stays tappable without a clickable modifier or a second layout pass. */
@Composable
private fun linkText(url: String) = buildAnnotatedString {
    withLink(
        LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary)),
        ),
    ) {
        append(url.removePrefix("https://"))
    }
}
