package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.GoalIntent
import com.appgate.brain.model.PageType
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.model.TaskStatus
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.test.FakeSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsiteLearningTest {
    private var humanRequests = 0
    private val engine = BrainEngine(FakeSite(), Memory(InMemoryStorage()), { null }, object : EngineEvents {
        override fun needHuman(ledger: TaskLedger, reason: String, url: String) { humanRequests++ }
    })

    private fun accept(ledger: TaskLedger, host: String, pageType: PageType, profile: SiteProfile): Boolean {
        val page = SpsParser().parse(FakeSite(ledger.host).apply { dialogShown = false }.observe(1000))
            .copy(url = "https://$host/", host = host, pageType = pageType, authWall = pageType == PageType.AUTH_WALL)
        return engine.javaClass.getDeclaredMethod("acceptPage", TaskLedger::class.java, SemanticPageState::class.java, SiteProfile::class.java)
            .apply { isAccessible = true }.invoke(engine, ledger, page, profile) as Boolean
    }

    private fun ledger(host: String, intent: GoalIntent = GoalIntent.LEARN_SITE) = TaskLedger(
        "offsite", GoalParser.parse("Ford Expedition").copy(intent = intent), host, "https://$host/"
    ).apply { status = TaskStatus.RUNNING; actions = 1 }

    @Test fun accountRedirectAfterTrainingClickEndsLessonWithoutHumanHold() {
        val task = ledger("classifieds.ksl.com")
        assertFalse(accept(task, "myaccount.ksl.com", PageType.AUTH_WALL, SiteProfiles.kslClassifieds))
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(0, humanRequests)
    }

    @Test fun backIntoOtherSitesHistoryEndsLessonWithoutHumanHold() {
        val task = ledger("classifieds.ksl.com")
        assertFalse(accept(task, "www.facebook.com", PageType.RESULTS, SiteProfiles.kslClassifieds))
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(0, humanRequests)
    }

    @Test fun carsListingOnClassifiedsIsStillPartOfTheCarsTask() {
        val task = ledger("cars.ksl.com")
        assertTrue(accept(task, "classifieds.ksl.com", PageType.DETAIL, SiteProfiles.kslCars))
        assertEquals(TaskStatus.RUNNING, task.status)
        assertEquals(0, humanRequests)
    }

    @Test fun initialAuthRedirectStillWaitsForHuman() {
        val task = ledger("classifieds.ksl.com").apply { actions = 0 }
        assertFalse(accept(task, "myaccount.ksl.com", PageType.AUTH_WALL, SiteProfiles.kslClassifieds))
        assertEquals(TaskStatus.NEED_HUMAN, task.status)
        assertEquals(1, humanRequests)
    }

    @Test fun assistModeStillStopsOnUnknownHost() {
        val task = ledger("classifieds.ksl.com", GoalIntent.FIND_LISTINGS)
        assertFalse(accept(task, "www.facebook.com", PageType.RESULTS, SiteProfiles.kslClassifieds))
        assertEquals(TaskStatus.NEED_HUMAN, task.status)
        assertEquals(1, humanRequests)
    }
}
