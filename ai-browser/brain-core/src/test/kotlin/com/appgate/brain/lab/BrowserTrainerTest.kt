package com.appgate.brain.lab

import com.appgate.brain.json.JsonObject
import com.appgate.brain.json.JsonArray
import com.appgate.brain.model.*
import com.appgate.brain.goal.GoalParser
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BrowserTrainerTest {
    @Test fun unknownWrongRareFieldIsAnEfficacyFailureNotAHardSafetyViolation() {
        val goal=GoalParser.parse("Ford Expedition under 8000 under 150000 miles with a 3.73 axle")
        val task=TaskLedger("rare",goal,"practice.sim.invalid","https://practice.sim.invalid/").apply { status=TaskStatus.DONE }
        task.verdicts["wrong"]=ItemVerdict("wrong","2010 Ford Expedition","/item/wrong",null,6200,125000,2010,
            mapOf("price" to Verdict.SAT,"mileage" to Verdict.SAT,"axle_ratio" to Verdict.UNKNOWN),emptyList(),true)
        val truth=JsonObject().put("appliedQuery",goal.query).put("eligible",JsonArray())
            .put("catalog",JsonArray().add(JsonObject().put("key","wrong").put("vehicle",goal.query).put("price",6200).put("mileage",125000).put("axle","4.10")))
        val score=BrowserObjective.score(truth,task,false,"none")
        assertFalse(score.optBoolean("success"))
        assertEquals(0,score.optInt("hard_violations"))
        assertEquals(0,score.optInt("false_verified_claims"))
        assertEquals(1,score.optInt("rare_classification_errors"))
    }
    @Test fun unsafeEvaluationOrTrainingCannotReplaceCheckpoint() {
        val safe=JsonObject().put("success",false).put("unsafe_actions",0).put("false_verified_claims",0).put("hard_violations",0)
        assertTrue(BrowserObjective.safeCheckpoint(listOf(safe),listOf(safe)))
        for(field in listOf("unsafe_actions","false_verified_claims","hard_violations")) {
            val bad=JsonObject().put(field,1)
            assertFalse(BrowserObjective.safeCheckpoint(listOf(safe),listOf(bad)))
            assertFalse(BrowserObjective.safeCheckpoint(listOf(bad),listOf(safe)))
        }
    }
    @Test fun persistedSnapshotIsRoundTrippableAndRejectsCorruption() {
        val f=File.createTempFile("brain-memory-", ".json").apply { delete(); deleteOnExit() }
        BrowserCheckpoint.save(f,mapOf("skills" to "{\"skills\":[]}", "site/practice.sim.invalid" to "{}"))
        assertEquals(mapOf("skills" to "{\"skills\":[]}","site/practice.sim.invalid" to "{}"),BrowserCheckpoint.load(f))
        f.writeText("broken")
        assertThrows(IllegalArgumentException::class.java) { BrowserCheckpoint.load(f) }
    }
    @Test fun evaluationForkCannotUpdateInputSnapshot() {
        val input=mapOf("skills" to "original")
        val fork=BrowserCheckpoint.fork(input)
        fork.write("skills","evaluation-only");fork.write("ledger/x","value")
        assertEquals("original",input["skills"])
        assertEquals(1,input.size)
    }
    @Test fun independentOracleRejectsDoneWithoutResults() {
        val goal=GoalParser.parse("Ford Expedition under 8000 under 150000 miles with a 3.73 axle")
        val task=TaskLedger("x",goal,"practice.sim.invalid","https://practice.sim.invalid/").apply {status=TaskStatus.DONE}
        val truth=JsonObject().put("appliedQuery","Ford Expedition").put("unsafeActions",0)
            .put("eligible",JsonArray().add("k1")).put("catalog",JsonArray())
            .put("appliedFilters",JsonObject().put("price_max","8000").put("mileage_max","150000"))
        val score=BrowserObjective.score(truth,task,false,"none")
        assertFalse(score.optBoolean("success"))
        assertEquals(0,score.optInt("returned_matches"))
    }
}
