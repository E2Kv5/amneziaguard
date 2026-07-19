package com.amneziaguard.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun byId(id: Long): ServerEntity?

    @Insert
    suspend fun insert(entity: ServerEntity): Long

    @Update
    suspend fun update(entity: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rules")
    fun observeAll(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules")
    suspend fun all(): List<AppRuleEntity>

    @Upsert
    suspend fun upsert(rule: AppRuleEntity)

    @Upsert
    suspend fun upsertAll(rules: List<AppRuleEntity>)

    @Query("DELETE FROM app_rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface AppGroupDao {
    @Query("SELECT * FROM app_groups ORDER BY id")
    fun observeAll(): Flow<List<AppGroupEntity>>

    @Query("SELECT * FROM app_groups WHERE id = :id")
    suspend fun byId(id: Long): AppGroupEntity?

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun members(groupId: Long): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members")
    fun observeMembers(): Flow<List<GroupMemberEntity>>

    @Insert
    suspend fun insert(group: AppGroupEntity): Long

    @Update
    suspend fun update(group: AppGroupEntity)

    @Query("DELETE FROM app_groups WHERE id = :id")
    suspend fun delete(id: Long)

    @Upsert
    suspend fun addMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND packageName = :packageName")
    suspend fun removeMember(groupId: Long, packageName: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: Long)
}
