package com.appgate.brain.engine

import com.appgate.brain.model.*
import com.appgate.brain.json.Json
import org.junit.Assert.*
import org.junit.Test

class RehearsalSpacingTest {
    private val now = 1_800_000_000_000L
    private fun site() = SiteModel("fake.market").also { site ->
        Curriculum.ensure(site)
        site.curriculum.filter { it.id != "dismiss_dialog" }.forEach { it.completedAt = now - 600_000 }
    }
    private fun page() = SemanticPageState(host = "fake.market", url = "https://fake.market/search", pageType = PageType.RESULTS, urlPattern = "/search", title = "", pageTypeConfidence = 1.0,
        regions = emptyList(), collections = emptyList(), constraintsActive = emptyMap(), challenge = false, authWall = false, dialogOpen = false, hash = "synthetic",
        settle = Settle.IDLE, affordances = listOf(Affordance("more", Role.LOAD_MORE), Affordance("next", Role.PAGE_NEXT)))
    @Test fun recentlyVerifiedRehearsalYieldsToAnotherAvailableSkill() {
        val site = site()
        Curriculum.markSkillVerified(site, "load_more", now)
        site.curriculum.single { it.id == "next_page" }.completedAt = now - 400_000
        assertEquals("next_page", Curriculum.practiceLesson(site, now + 30_000, page()))
    }
    @Test fun unchangedCompletedSkillsAreNotRehearsedEveryThirtySecondsAfterReload() {
        val site = site()
        Curriculum.markSkillVerified(site, "load_more", now)
        Curriculum.markSkillVerified(site, "next_page", now)
        val restored = SiteModel.fromJson(Json.parseObject(site.toJson().toString()))
        assertEquals("", Curriculum.practiceLesson(restored, now - 1, page()))
        assertEquals("", Curriculum.practiceLesson(restored, now + 30_000, page()))
        assertEquals("", Curriculum.practiceLesson(restored, now + 299_999, page()))
        assertFalse(restored.curriculum.single { it.id == "dismiss_dialog" }.done)
        assertTrue(Curriculum.practiceLesson(restored, now + 300_000, page()) in setOf("load_more", "next_page"))
    }
    @Test fun pendingLessonsRemainImmediatelyEligibleDespiteRecentCompletedSkills() {
        val site = site()
        site.curriculum.single { it.id == "next_page" }.completedAt = 0
        Curriculum.markSkillVerified(site, "load_more", now)
        Curriculum.observe(site, page(), now)
        assertEquals("next_page", Curriculum.nextLesson(site, now, page()))
    }
}
