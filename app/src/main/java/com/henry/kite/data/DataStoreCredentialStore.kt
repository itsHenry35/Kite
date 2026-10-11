package com.henry.kite.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.henry.kite.airplay.pairing.PairingCredentials
import com.henry.kite.airplay.pairing.PairingProtocol
import com.henry.kite.airplay.session.CredentialStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kite_pairings",
)

/**
 * Persists pairing credentials in DataStore.
 *
 * This holds an Ed25519 private seed. App-private storage keeps it away from other
 * apps, but it is not hardware-backed: the seed has to leave any keystore to sign
 * pair-verify M3, and StrongBox does not expose raw Ed25519 signing on every
 * device. Worst case is that someone with the file can mirror to the user's
 * Apple TV, which is why re-pairing on rejection is cheap.
 */
class DataStoreCredentialStore(private val context: Context) : CredentialStore {

    override suspend fun load(receiverId: String): PairingCredentials? {
        val raw = context.credentialDataStore.data.first()[key(receiverId)] ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    override suspend fun save(receiverId: String, credentials: PairingCredentials, receiverName: String?) {
        context.credentialDataStore.edit { prefs ->
            prefs[key(receiverId)] = encode(credentials)
            if (receiverName != null) prefs[nameKey(receiverId)] = receiverName
        }
    }

    override suspend fun forget(receiverId: String) {
        context.credentialDataStore.edit { prefs -> prefs.remove(key(receiverId)) }
    }

    override suspend fun pairedReceiverIds(): Set<String> = context.credentialDataStore.data.first()
        .asMap()
        .keys
        .map { it.name }
        .filter { it.startsWith(KEY_PREFIX) }
        .map { it.removePrefix(KEY_PREFIX) }
        .toSet()

    override suspend fun receiverName(receiverId: String): String? =
        context.credentialDataStore.data.first()[nameKey(receiverId)]

    override suspend fun forgetReceiver(receiverId: String) {
        context.credentialDataStore.edit { prefs ->
            prefs.remove(key(receiverId))
            prefs.remove(nameKey(receiverId))
            prefs.remove(passwordKey(receiverId))
        }
    }

    override suspend fun loadPassword(receiverId: String): String? =
        context.credentialDataStore.data.first()[passwordKey(receiverId)]

    override suspend fun savePassword(receiverId: String, password: String) {
        context.credentialDataStore.edit { prefs ->
            prefs[passwordKey(receiverId)] = password
        }
    }

    override suspend fun forgetPassword(receiverId: String) {
        context.credentialDataStore.edit { prefs -> prefs.remove(passwordKey(receiverId)) }
    }

    private fun key(receiverId: String) = stringPreferencesKey("$KEY_PREFIX$receiverId")

    private fun nameKey(receiverId: String) = stringPreferencesKey("$NAME_KEY_PREFIX$receiverId")

    private fun passwordKey(receiverId: String) = stringPreferencesKey("$PASSWORD_KEY_PREFIX$receiverId")

    private fun encode(credentials: PairingCredentials): String = JSONObject().apply {
        put(FIELD_CLIENT_ID, credentials.clientId)
        put(FIELD_CLIENT_SEED, credentials.clientSeed.toBase64())
        put(FIELD_RECEIVER_KEY, credentials.receiverPublicKey.toBase64())
        put(FIELD_RECEIVER_ID, credentials.receiverId)
        put(FIELD_PROTOCOL, credentials.protocol.name)
    }.toString()

    private fun decode(raw: String): PairingCredentials {
        val json = JSONObject(raw)
        return PairingCredentials(
            clientId = json.getString(FIELD_CLIENT_ID),
            clientSeed = json.getString(FIELD_CLIENT_SEED).fromBase64(),
            receiverPublicKey = json.getString(FIELD_RECEIVER_KEY).fromBase64(),
            receiverId = json.getString(FIELD_RECEIVER_ID),
            protocol = PairingProtocol.fromStored(json.optString(FIELD_PROTOCOL)),
        )
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val KEY_PREFIX = "pairing/"
        const val NAME_KEY_PREFIX = "name/"
        const val PASSWORD_KEY_PREFIX = "password/"
        const val FIELD_CLIENT_ID = "clientId"
        const val FIELD_CLIENT_SEED = "clientSeed"
        const val FIELD_RECEIVER_KEY = "receiverPublicKey"
        const val FIELD_RECEIVER_ID = "receiverId"
        const val FIELD_PROTOCOL = "protocol"
    }
}
