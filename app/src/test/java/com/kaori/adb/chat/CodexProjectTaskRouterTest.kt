package com.kaori.adb.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProjectTaskRouterTest {
    @Test
    fun projectTaskRoutesAnySafeProjectThroughAcsSkillAndPreservesVisibleInput() {
        val original = "项目：学习助手  任务：修复返回键\n同时使用 " + 36.toChar() + "android-phone-adb"

        val routed = CodexProjectTaskRouter.prepareForHarness(original)

        assertTrue(routed.startsWith("[CODEX_WEB_SKILL_ROUTING_V1]"))
        assertTrue(routed.contains("required_skills=acs-android-project,android-phone-adb"))
        assertTrue(routed.contains("ACS_PROJECT_NAME=学习助手"))
        assertTrue(routed.contains("ACS_PROJECT_ROOT=/storage/emulated/0/AndroidIDEProjects/学习助手"))
        assertEquals(original, CodexProjectTaskRouter.displayContent("user", routed))
    }

    @Test
    fun explicitDollarSkillsRouteWithoutProjectAndDoNotAlterOtherMessages() {
        val explicit = "请先使用 " + 36.toChar() + "skill-a 和 " + 36.toChar() + "skill-b 处理这个任务"
        val ordinary = "普通聊天内容"

        val routed = CodexProjectTaskRouter.prepareForHarness(explicit)

        assertTrue(routed.contains("required_skills=skill-a,skill-b"))
        assertEquals(explicit, CodexProjectTaskRouter.displayContent("user", routed))
        assertEquals(ordinary, CodexProjectTaskRouter.prepareForHarness(ordinary))
    }
    @Test
    fun routingTitlesAndPreviewsNeverExposeInternalMetadata() {
        val routed = CodexProjectTaskRouter.prepareForHarness("项目：ADB  任务：修改状态文案")

        assertEquals("项目：ADB  任务：修改状态文案", CodexProjectTaskRouter.displayTitle(routed))
        assertEquals("项目任务", CodexProjectTaskRouter.displayTitle("[CODEX_WEB_SKILL_ROUTING_V1] This is…"))
    }
}
