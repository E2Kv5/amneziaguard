package com.amneziaguard.core.data.repo

import com.amneziaguard.core.data.db.AppGroupDao
import com.amneziaguard.core.data.db.AppGroupEntity
import com.amneziaguard.core.data.db.AppRuleDao
import com.amneziaguard.core.data.db.AppRuleEntity
import com.amneziaguard.core.data.db.GroupMemberEntity
import com.amneziaguard.core.data.db.ServerDao
import com.amneziaguard.core.data.db.ServerEntity
import com.amneziaguard.core.data.secret.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeServerDao : ServerDao {
    private val items = MutableStateFlow<List<ServerEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<ServerEntity>> = items
    override suspend fun byId(id: Long): ServerEntity? = items.value.firstOrNull { it.id == id }
    override suspend fun insert(entity: ServerEntity): Long {
        val id = nextId++
        items.value = items.value + entity.copy(id = id)
        return id
    }
    override suspend fun update(entity: ServerEntity) {
        items.value = items.value.map { if (it.id == entity.id) entity else it }
    }
    override suspend fun delete(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }
}

class FakeSecretStore : SecretStore {
    val privateKeys = mutableMapOf<Long, String>()
    val psks = mutableMapOf<Pair<Long, Int>, String>()

    override fun putPrivateKey(serverId: Long, key: String) { privateKeys[serverId] = key }
    override fun getPrivateKey(serverId: Long): String? = privateKeys[serverId]
    override fun putPresharedKey(serverId: Long, peerIndex: Int, psk: String) { psks[serverId to peerIndex] = psk }
    override fun getPresharedKey(serverId: Long, peerIndex: Int): String? = psks[serverId to peerIndex]
    override fun deleteFor(serverId: Long) {
        privateKeys.remove(serverId)
        psks.keys.filter { it.first == serverId }.forEach { psks.remove(it) }
    }
}

class FakeAppRuleDao : AppRuleDao {
    private val rules = MutableStateFlow<List<AppRuleEntity>>(emptyList())
    override fun observeAll(): Flow<List<AppRuleEntity>> = rules
    override suspend fun all(): List<AppRuleEntity> = rules.value
    override suspend fun upsert(rule: AppRuleEntity) {
        rules.value = rules.value.filterNot { it.packageName == rule.packageName } + rule
    }
    override suspend fun upsertAll(rulesToAdd: List<AppRuleEntity>) {
        val names = rulesToAdd.map { it.packageName }.toSet()
        rules.value = rules.value.filterNot { it.packageName in names } + rulesToAdd
    }
    override suspend fun delete(packageName: String) {
        rules.value = rules.value.filterNot { it.packageName == packageName }
    }
}

class FakeAppGroupDao : AppGroupDao {
    private val groups = MutableStateFlow<List<AppGroupEntity>>(emptyList())
    private val members = MutableStateFlow<List<GroupMemberEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<AppGroupEntity>> = groups
    override suspend fun byId(id: Long): AppGroupEntity? = groups.value.firstOrNull { it.id == id }
    override suspend fun members(groupId: Long): List<GroupMemberEntity> =
        members.value.filter { it.groupId == groupId }
    override fun observeMembers(): Flow<List<GroupMemberEntity>> = members
    override suspend fun insert(group: AppGroupEntity): Long {
        val id = nextId++
        groups.value = groups.value + group.copy(id = id)
        return id
    }
    override suspend fun update(group: AppGroupEntity) {
        groups.value = groups.value.map { if (it.id == group.id) group else it }
    }
    override suspend fun delete(id: Long) {
        groups.value = groups.value.filterNot { it.id == id }
    }
    override suspend fun addMember(member: GroupMemberEntity) {
        members.value = members.value.filterNot { it.groupId == member.groupId && it.packageName == member.packageName } + member
    }
    override suspend fun removeMember(groupId: Long, packageName: String) {
        members.value = members.value.filterNot { it.groupId == groupId && it.packageName == packageName }
    }
    override suspend fun clearMembers(groupId: Long) {
        members.value = members.value.filterNot { it.groupId == groupId }
    }
}
