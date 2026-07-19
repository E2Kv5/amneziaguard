package com.amneziaguard.core.data.repo

import com.amneziaguard.core.data.model.AppMode
import com.amneziaguard.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the built-in app-group presets on first run. Members are curated common
 * package names; those not installed are simply ignored when the group is
 * applied. Users can edit membership afterwards.
 */
@Singleton
class GroupSeeder @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun seedIfNeeded() {
        if (settingsRepository.isGroupsSeeded()) return
        ruleRepository.createGroup("Messengers → VPN", AppMode.VPN, MESSENGERS)
        ruleRepository.createGroup("Banking → Direct", AppMode.BYPASS, BANKING)
        ruleRepository.createGroup("Trackers/Ads → Block", AppMode.BLOCK, TRACKERS)
        settingsRepository.markGroupsSeeded()
    }

    private companion object {
        val MESSENGERS = listOf(
            "org.telegram.messenger",
            "org.thoughtcrime.securesms", // Signal
            "com.whatsapp",
            "org.torproject.android",
        )
        val BANKING = listOf(
            "com.google.android.apps.walletnfcrel",
        )
        val TRACKERS = listOf(
            "com.google.android.gms.ads", // representative; often part of GMS
        )
    }
}
