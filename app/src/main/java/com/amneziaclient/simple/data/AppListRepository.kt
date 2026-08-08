package com.amneziaclient.simple.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

@Singleton
class AppListRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("securePrefs") private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_SELECTED_APPS = "selected_apps"
    }

    /**
     * Возвращает все приложения с иконкой на экране приложений — то есть те,
     * что пользователь реально видит и запускает: включая предустановленные
     * системой (YouTube, Chrome, Play Маркет, Google-приложения и т.п.), а не
     * только установленные пользователем вручную.
     *
     * Раньше здесь фильтровались все приложения с флагом FLAG_SYSTEM, из-за
     * чего пропадали предустановленные производителем/Google приложения —
     * это и была причина бага "нет YouTube и других стандартных приложений".
     * Правильный признак "видимое пользователю приложение" — наличие
     * launcher-активности (ACTION_MAIN + CATEGORY_LAUNCHER), а не флаг system.
     */
    suspend fun getUserApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val ownPackage = context.packageName

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)

        resolveInfos.asSequence()
            .map { it.activityInfo.packageName }
            .filter { it != ownPackage }
            .distinct()
            .mapNotNull { packageName ->
                runCatching {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    InstalledAppInfo(
                        packageName = packageName,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun getSelectedPackages(): Set<String> =
        prefs.getStringSet(KEY_SELECTED_APPS, emptySet())?.toSet() ?: emptySet()

    fun setSelectedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_APPS, packages).apply()
    }

    fun selectedCount(): Int = getSelectedPackages().size
}
