package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.model.SiteModel
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class SubmittedSearchTest {
    @Test fun draftSearchOnHomeIsNotSubmittedSearch() {
        val site = FakeSite().apply { dialogShown = false; query = "Ford Expedition" }
        val state = SpsParser().parse(site.observe(1000))
        val policy = TaskPolicy(SiteProfiles.generic(site.host), SiteModel(site.host))
        assertFalse("An unsent draft must still go through search", policy.queryApplied(GoalParser.parse("Ford Expedition"), state))
    }
}
