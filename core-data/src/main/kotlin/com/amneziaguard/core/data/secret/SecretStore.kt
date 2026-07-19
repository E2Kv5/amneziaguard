package com.amneziaguard.core.data.secret

/**
 * Storage for tunnel secrets. Private and preshared keys never touch Room —
 * only this store, backed by EncryptedSharedPreferences (Android Keystore
 * master key).
 */
interface SecretStore {
    fun putPrivateKey(serverId: Long, key: String)
    fun getPrivateKey(serverId: Long): String?
    fun putPresharedKey(serverId: Long, peerIndex: Int, psk: String)
    fun getPresharedKey(serverId: Long, peerIndex: Int): String?
    fun deleteFor(serverId: Long)
}
