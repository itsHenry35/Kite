package com.henry.kite.ui

/**
 * One stored pairing, as the Saved receivers screen shows it.
 *
 * @param stableId the credential-store key (the receiver's mDNS deviceId, or
 *   `host:port` when it has none) — shown as the supporting line, because two
 *   receivers can carry the same display name.
 * @param name the display name captured at pairing time; null for entries
 *   stored before names were persisted.
 */
data class SavedReceiver(val stableId: String, val name: String?)
