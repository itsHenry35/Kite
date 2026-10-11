package com.henry.kite.airplay.session

import com.henry.kite.airplay.pairing.PairingCredentials

/**
 * Persistence for pair-setup results, keyed by [com.henry.kite.airplay.discovery.AirPlayReceiver.stableId].
 *
 * Implementations hold an Ed25519 private seed, so app-private storage is the
 * minimum. The app module backs this with DataStore.
 */
interface CredentialStore {
    suspend fun load(receiverId: String): PairingCredentials?

    /**
     * @param receiverName display name as seen at pairing time; persisted for the
     *   Saved receivers screen — the id alone is not human-readable. Null leaves
     *   any previously stored name untouched.
     */
    suspend fun save(receiverId: String, credentials: PairingCredentials, receiverName: String? = null)

    suspend fun forget(receiverId: String)

    suspend fun pairedReceiverIds(): Set<String>

    /** The display name captured at pairing time; null for older entries. */
    suspend fun receiverName(receiverId: String): String? = null

    /** Erases everything stored for this receiver: credentials, name, and password. */
    suspend fun forgetReceiver(receiverId: String) {
        forget(receiverId)
        forgetPassword(receiverId)
    }

    /** The AirPlay password saved for this receiver, if the user chose to keep it. */
    suspend fun loadPassword(receiverId: String): String? = null

    suspend fun savePassword(receiverId: String, password: String) = Unit

    suspend fun forgetPassword(receiverId: String) = Unit
}

/** Non-persistent store, for tests and for the first run before storage is wired up. */
class InMemoryCredentialStore : CredentialStore {
    private val entries = mutableMapOf<String, PairingCredentials>()
    private val names = mutableMapOf<String, String>()

    override suspend fun load(receiverId: String): PairingCredentials? = entries[receiverId]

    override suspend fun save(receiverId: String, credentials: PairingCredentials, receiverName: String?) {
        entries[receiverId] = credentials
        if (receiverName != null) names[receiverId] = receiverName
    }

    override suspend fun forget(receiverId: String) {
        entries.remove(receiverId)
    }

    override suspend fun pairedReceiverIds(): Set<String> = entries.keys.toSet()

    override suspend fun receiverName(receiverId: String): String? = names[receiverId]

    override suspend fun forgetReceiver(receiverId: String) {
        super.forgetReceiver(receiverId)
        names.remove(receiverId)
    }
}
