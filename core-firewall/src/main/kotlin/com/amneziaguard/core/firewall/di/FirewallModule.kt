package com.amneziaguard.core.firewall.di

import com.amneziaguard.core.firewall.leak.LeakCheckApi
import com.amneziaguard.core.firewall.leak.MullvadLeakCheckApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FirewallModule {

    @Binds
    abstract fun leakCheckApi(impl: MullvadLeakCheckApi): LeakCheckApi
}
