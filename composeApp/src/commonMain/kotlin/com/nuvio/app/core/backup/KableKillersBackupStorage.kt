package com.nuvio.app.core.backup

/** App-private key/value storage for the Kable Killers backup (device key and status). */
internal expect object KableKillersBackupStorage {
    fun load(key: String): String?
    fun save(key: String, value: String?)
}
