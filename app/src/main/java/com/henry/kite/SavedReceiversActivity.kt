package com.henry.kite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.henry.kite.ui.SavedReceiver
import com.henry.kite.ui.SavedReceiversScreen
import com.henry.kite.ui.theme.KiteTheme
import kotlinx.coroutines.launch

/**
 * Settings → Saved receivers: everything with stored pairing material, with a
 * per-entry forget action. Forgetting erases the credentials, the stored name,
 * and any saved AirPlay password, so the next connection pairs from scratch.
 */
class SavedReceiversActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KiteTheme {
                var receivers by remember { mutableStateOf<List<SavedReceiver>>(emptyList()) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) { receivers = loadReceivers() }

                SavedReceiversScreen(
                    receivers = receivers,
                    onForget = { row ->
                        scope.launch {
                            (application as KiteApplication).credentialStore
                                .forgetReceiver(row.stableId)
                            receivers = loadReceivers()
                        }
                    },
                    onBack = { finish() },
                )
            }
        }
    }

    private suspend fun loadReceivers(): List<SavedReceiver> {
        val store = (application as KiteApplication).credentialStore
        return store.pairedReceiverIds()
            .map { SavedReceiver(stableId = it, name = store.receiverName(it)) }
            .sortedBy { (it.name ?: it.stableId).lowercase() }
    }
}
