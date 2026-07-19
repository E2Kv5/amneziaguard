package com.amneziaguard.core.data.secret

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedPrefsSecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecretStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "amneziaguard_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun putPrivateKey(serverId: Long, key: String) {
        prefs.edit().putString(pkKey(serverId), key).apply()
    }

    override fun getPrivateKey(serverId: Long): String? =
        prefs.getString(pkKey(serverId), null)

    override fun putPresharedKey(serverId: Long, peerIndex: Int, psk: String) {
        prefs.edit().putString(pskKey(serverId, peerIndex), psk).apply()
    }

    override fun getPresharedKey(serverId: Long, peerIndex: Int): String? =
        prefs.getString(pskKey(serverId, peerIndex), null)

    override fun deleteFor(serverId: Long) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it == pkKey(serverId) || it.startsWith("psk_${serverId}_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun pkKey(serverId: Long) = "pk_$serverId"
    private fun pskKey(serverId: Long, peerIndex: Int) = "psk_${serverId}_$peerIndex"
}
