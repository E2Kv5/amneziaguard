package com.amneziaguard.core.firewall

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AppInfo(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
)

@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pm: PackageManager get() = context.packageManager

    /** Apps that request INTERNET, sorted by label. Own package excluded. */
    suspend fun installedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_PERMISSIONS
        pm.getInstalledPackages(flags)
            .asSequence()
            .filter { pkg ->
                pkg.packageName != context.packageName &&
                    pkg.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
            }
            .mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                AppInfo(
                    packageName = pkg.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    uid = appInfo.uid,
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun icon(packageName: String): Drawable? =
        runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
}
