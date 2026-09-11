package com.kaori.adb.agent

import android.content.Context

class SkillRepository(private val context: Context) {
    fun loadCoreSkills(): String {
        val paths = listOf("skills/adb/SKILL.md", "skills/android-ui/SKILL.md")
        return paths.joinToString("\n\n") { path ->
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
    }
}
