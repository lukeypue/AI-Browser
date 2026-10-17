package com.appgate.tv.serverlab
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.memory.Memory
import com.appgate.brain.memory.FileBrainStorage
import com.appgate.brain.model.*
import com.appgate.brain.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ServerLabRuntimeTest {
    @Test fun inactiveSeamPreservesProductionProfiles() {
        ServerLabRuntime.detach()
        val expected = SiteProfiles.training.filter { !it.requiresLogin }
        assertEquals(expected, ServerLabRuntime.profiles(expected))
        assertFalse(ServerLabRuntime.active)
    }
    @Test fun observerCannotActivateWithoutExplicitInstallation() {
        ServerLabRuntime.detach()
        ServerLabRuntime.observe(JsonObject().put("unsafe", "private"))
        assertFalse(ServerLabRuntime.active)
    }
    @Test fun memoryFingerprintSurvivesReloadAndExcludesVolatileData() {
        val dir=Files.createTempDirectory("serverlab-memory").toFile()
        try {
            val memory=Memory(FileBrainStorage(dir))
            val site=memory.site("fixture.invalid")
            site.curriculum.add(CurriculumItem("search", "private description", completedAt=10L))
            site.lastSeenAt=20L
            memory.saveSite(site)
            val first=ServerLabRuntime.project(memory)
            site.lastSeenAt=999L
            site.curriculum[0].completedAt=888L
            memory.saveSite(site)
            val second=ServerLabRuntime.project(Memory(FileBrainStorage(dir)))
            assertEquals(first, second)
            assertEquals(1,first.optInt("verified_lessons"))
            assertEquals(setOf("fingerprint","verified_lessons","verified_skills","failed_strategies"),first.keys)
            assertFalse(first.toString().contains("private"))
            site.curriculum.add(CurriculumItem("paginate", "private", completedAt=10L))
            memory.saveSite(site)
            assertNotEquals(first.optString("fingerprint"),ServerLabRuntime.project(memory).optString("fingerprint"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun literalQueryChangesDoNotChangeProceduralProjection() {
        val dir=Files.createTempDirectory("serverlab-skill").toFile()
        try {
            val m=Memory(FileBrainStorage(dir))
            val skill=Skill("private-id",1,"private intent",emptyList(),emptyList(),
                listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="private query")),emptyList(),SkillOrigin.COMPILED,
                tags=setOf("verified_v2"))
            m.skills.put(skill)
            val a=ServerLabRuntime.project(m)
            m.skills.put(skill.copy(id="private-id",body=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="different query"))))
            assertEquals(a,ServerLabRuntime.project(m))
            m.skills.put(skill.copy(body=listOf(Step(StepKind.CLICK,Role.SUBMIT))))
            assertNotEquals(a,ServerLabRuntime.project(m))
        } finally {dir.deleteRecursively()}
    }
    @Test fun completionRequiresRealDeadlineAndVerifiedLearning() {
        val flags=JsonObject().put("interrupted",false).put("clock_changed",false)
        val summary=JsonObject().put("end_reason","deadline").put("verified",3).put("verified_skills",1)
        val report=JsonObject().put("elapsed_ms",1_800_000L).put("flags",flags).put("summary",summary)
        val memory=JsonObject().put("verified_lessons",1).put("verified_skills",1)
        assertTrue(ServerLabRuntime.qualifiesCompleted(report,memory))
        report.put("elapsed_ms",1_799_999L)
        assertFalse(ServerLabRuntime.qualifiesCompleted(report,memory))
        report.put("elapsed_ms",1_800_000L)
        flags.put("clock_changed",true)
        assertFalse(ServerLabRuntime.qualifiesCompleted(report,memory))
        flags.put("clock_changed",false).put("interrupted",true)
        assertFalse(ServerLabRuntime.qualifiesCompleted(report,memory))
        flags.put("interrupted",false)
        summary.put("verified_skills",0)
        assertFalse(ServerLabRuntime.qualifiesCompleted(report,memory))
        summary.put("verified_skills",1)
        memory.put("verified_lessons",0)
        assertFalse(ServerLabRuntime.qualifiesCompleted(report,memory))
    }
    @Test fun teacherOffCycleCanRetainLessonsWithoutCompiledSkills() {
        val report=JsonObject().put("elapsed_ms",1_800_000L)
            .put("flags",JsonObject().put("interrupted",false).put("clock_changed",false))
            .put("summary",JsonObject().put("end_reason","deadline").put("verified",4).put("verified_skills",3))
        val retained=JsonObject().put("verified_lessons",3).put("verified_skills",0)
        assertTrue(ServerLabRuntime.qualifiesCompleted(report,retained))
        assertEquals(0,retained.optInt("verified_skills"))
        retained.put("verified_lessons",0)
        assertFalse(ServerLabRuntime.qualifiesCompleted(report,retained))
    }
}
