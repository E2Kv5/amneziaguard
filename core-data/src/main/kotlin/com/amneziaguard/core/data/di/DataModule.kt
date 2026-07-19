package com.amneziaguard.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.amneziaguard.core.data.db.AmneziaGuardDatabase
import com.amneziaguard.core.data.db.AppGroupDao
import com.amneziaguard.core.data.db.AppRuleDao
import com.amneziaguard.core.data.db.ServerDao
import com.amneziaguard.core.data.secret.EncryptedPrefsSecretStore
import com.amneziaguard.core.data.secret.SecretStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AmneziaGuardDatabase =
        Room.databaseBuilder(context, AmneziaGuardDatabase::class.java, "amneziaguard.db")
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()

    @Provides
    fun serverDao(db: AmneziaGuardDatabase): ServerDao = db.serverDao()

    @Provides
    fun appRuleDao(db: AmneziaGuardDatabase): AppRuleDao = db.appRuleDao()

    @Provides
    fun appGroupDao(db: AmneziaGuardDatabase): AppGroupDao = db.appGroupDao()

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecretModule {

    @Binds
    abstract fun secretStore(impl: EncryptedPrefsSecretStore): SecretStore
}
