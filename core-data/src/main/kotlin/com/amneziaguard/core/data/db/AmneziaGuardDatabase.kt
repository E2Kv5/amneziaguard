package com.amneziaguard.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerEntity::class,
        AppRuleEntity::class,
        AppGroupEntity::class,
        GroupMemberEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AmneziaGuardDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun appRuleDao(): AppRuleDao
    abstract fun appGroupDao(): AppGroupDao
}
