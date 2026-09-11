package com.kaori.adb.tools

import android.content.Context
import android.content.Intent

data class AppCandidate(
    val label: String,
    val packageName: String
)

object AppResolver {
    fun resolveCandidates(context: Context, target: String): List<AppCandidate> {
        val value = target.trim()
        if (value.isBlank()) return emptyList()

        val packageManager = context.packageManager
        val exactApplication = runCatching {
            packageManager.getApplicationInfo(value, 0)
        }.getOrNull()
        if (exactApplication != null) {
            return listOf(
                AppCandidate(
                    packageManager.getApplicationLabel(exactApplication).toString(),
                    value
                )
            )
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .map { info ->
                AppCandidate(
                    info.loadLabel(packageManager).toString(),
                    info.activityInfo.packageName
                )
            }
            .filter { candidate -> candidate.label.equals(value, ignoreCase = true) }
            .distinctBy { it.packageName }
            .sortedBy { it.packageName }
    }
}
