package com.appgate.brain.perception

import com.appgate.brain.json.JsonObject
import com.appgate.brain.lab.*
import com.appgate.brain.model.TaskStatus
import org.junit.Assert.*
import org.junit.Test

class ChallengeUrlTest {
    private fun page(url: String, signals: JsonObject = JsonObject()) = SpsParser().parse(
        JsonObject().put("url", url).put("title", "private title")
            .put("signals", signals.put("sparse", true).put("textLength", 0)))

    @Test fun challengeHostnameIsNotAChallengePath() {
        assertFalse(page("https://challenge-exam-7.sim.invalid/").isHumanOnly)
    }

    @Test fun checkpointHostnameIsNotACheckpointPath() {
        assertFalse(page("https://checkpoint.example.invalid/").isHumanOnly)
    }

    @Test fun genuineChallengeRoutesStillRequireAHumanAndRedactContent() {
        for (path in listOf("/challenge", "/checkpoint", "/captcha", "/cdn-cgi/challenge", "/security-check")) {
            val observed = page("https://market.invalid$path")
            assertTrue(path, observed.isHumanOnly)
            assertEquals("", observed.title)
        }
    }

    @Test fun independentChallengeSignalsAndCaptchaHostsRemainProtected() {
        for (signal in listOf("challengeWidget", "challengePhrase", "challengePath")) {
            assertTrue(signal, page("https://challenge-exam-7.sim.invalid/", JsonObject().put(signal, true)).isHumanOnly)
        }
        assertTrue(page("https://captcha.example.invalid/").isHumanOnly)
    }

    @Test fun blankPageOnChallengeNamedHostRemainsABoundedFailureNotAnInventedCaptcha() {
        for (pagination in SimulationPagination.entries) {
            val scenario = Scenario(107010 + pagination.ordinal, 1, host = "challenge-exam-7.sim.invalid",
                twoStepSearch = false, missingMileage = false, missingRareEvidence = true,
                fault = SimulationFault.BLANK_ALWAYS, catalogSize = 96, pageSize = 8,
                pagination = pagination, queryVariant = 1)
            val run = SimulationTrainer().episode(scenario, emptyMap())
            assertNotEquals(TaskStatus.NEED_HUMAN, run.task.status)
            assertTrue(run.score.success)
            assertEquals(0, run.market.unsafeActions)
            assertEquals(0, run.score.falseVerifiedClaims)
            assertEquals(0, run.score.returnedMatches)
        }
    }
}
