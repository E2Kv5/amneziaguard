package com.amneziaguard.core.data.repo

import com.amneziaguard.core.data.db.AppGroupDao
import com.amneziaguard.core.data.db.AppGroupEntity
import com.amneziaguard.core.data.db.AppRuleDao
import com.amneziaguard.core.data.db.AppRuleEntity
import com.amneziaguard.core.data.db.GroupMemberEntity
import com.amneziaguard.core.data.model.AppMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppGroup(
    val id: Long,
    val name: String,
    val mode: AppMode,
    val members: List<String>,
)

@Singleton
class RuleRepository @Inject constructor(
    private val ruleDao: AppRuleDao,
    private val groupDao: AppGroupDao,
) {
    fun observeRules(): Flow<Map<String, AppMode>> =
        ruleDao.observeAll().map { rules ->
            rules.associate { it.packageName to AppMode.fromId(it.mode) }
        }

    suspend fun rules(): Map<String, AppMode> =
        ruleDao.all().associate { it.packageName to AppMode.fromId(it.mode) }

    /** null mode clears the explicit rule, returning the app to the default mode. */
    suspend fun setMode(packageName: String, mode: AppMode?) {
        if (mode == null) {
            ruleDao.delete(packageName)
        } else {
            ruleDao.upsert(AppRuleEntity(packageName, mode.id))
        }
    }

    fun observeGroups(): Flow<List<AppGroupEntity>> = groupDao.observeAll()

    fun observeGroupMembers(): Flow<List<GroupMemberEntity>> = groupDao.observeMembers()

    suspend fun createGroup(name: String, mode: AppMode, members: List<String>): Long {
        val id = groupDao.insert(AppGroupEntity(name = name, mode = mode.id))
        members.forEach { groupDao.addMember(GroupMemberEntity(id, it)) }
        return id
    }

    suspend fun updateGroup(id: Long, name: String, mode: AppMode, members: List<String>) {
        groupDao.update(AppGroupEntity(id = id, name = name, mode = mode.id))
        groupDao.clearMembers(id)
        members.forEach { groupDao.addMember(GroupMemberEntity(id, it)) }
    }

    suspend fun deleteGroup(id: Long) {
        groupDao.clearMembers(id)
        groupDao.delete(id)
    }

    /** Applies the group's mode as an explicit rule to every member app. */
    suspend fun applyGroup(id: Long) {
        val group = groupDao.byId(id) ?: return
        val members = groupDao.members(id)
        ruleDao.upsertAll(members.map { AppRuleEntity(it.packageName, group.mode) })
    }
}
