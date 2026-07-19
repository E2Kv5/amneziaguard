package com.amneziaguard.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Saved server configuration. [confBody] is the .conf text WITHOUT the
 * PrivateKey and PresharedKey lines — secrets live only in the SecretStore
 * (EncryptedSharedPreferences / Android Keystore).
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val confBody: String,
    val endpoint: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
)

@Entity(tableName = "app_rules")
data class AppRuleEntity(
    @PrimaryKey val packageName: String,
    val mode: Int,
)

@Entity(tableName = "app_groups")
data class AppGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mode: Int,
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "packageName"])
data class GroupMemberEntity(
    val groupId: Long,
    val packageName: String,
)
