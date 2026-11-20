package com.appgate.brain.lab

import com.appgate.brain.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MarketLearningDistrictTest {
    private fun experiment(): JsonObject {
        val dir = Files.createTempDirectory("market-district-test-").toFile()
        try {
            return MarketLearningDistrict().experiment(dir)
        } finally { dir.deleteRecursively() }
    }

    @Test fun frozenDistrictSeparatesTrainingAndWithheldLayouts() {
        val r = experiment()
        assertEquals(56, r.optInt("training_cases"))
        assertEquals(336, r.optInt("evaluation_cases"))
        assertTrue(r.optBoolean("splits_disjoint"))
        assertEquals(7, r.optArray("capabilities")!!.size)
        assertEquals(64, r.optString("evaluation_manifest_sha256").length)
        assertEquals("861bd06ded70c658f3f8eae2482fef32606488a8f3f4bf988016cd878e5947b2", r.optString("training_manifest_sha256"))
        assertEquals("031f1a13f55b51b50592250060775bcb19261ded5bc1a0fdb784124eaf26c511", r.optString("evaluation_manifest_sha256"))
        assertEquals(MarketLearningDistrict.cases(false), MarketLearningDistrict.cases(false))
        assertTrue(MarketLearningDistrict.cases(false).all { java.net.URI("https://${it.host}").host == it.host })
        assertFalse(r.optBoolean("real_world_validated", true))
    }

    @Test fun acknowledgedNoOpsAndHumanPagesCannotBorrowSuccess() {
        val r = experiment()
        assertEquals(0, r.optInt("false_verified_claims", -1))
        assertEquals(0, r.optInt("human_actions", -1))
        assertEquals(0, r.optInt("unsafe_actions", -1))
        assertEquals(0, r.optInt("evaluation_teacher_calls", -1))
        assertTrue(r.optBoolean("negative_controls_passed"))
        assertTrue("every evaluated capability must actually be observable", r.optBoolean("observation_gates_passed"))
        assertTrue(r.optArray("cold")!!.objects().filter { it.optString("variant") == "NOOP" }.all { it.optInt("actions") > 0 })
    }

    @Test fun evaluationForksPreserveReloadedTrainingMemory() {
        val r = experiment()
        assertTrue(r.optBoolean("disk_reload_verified"))
        assertTrue(r.optBoolean("frozen_memory_unchanged"))
        assertEquals("LOCAL_SCRIPTED", r.optString("teacher"))
        assertTrue(r.has("cold_successes"))
        assertTrue(r.has("retained_successes"))
        assertTrue(r.has("ablation_successes"))
        assertTrue(r.has("attributed_learning_gain"))
        if (r.optInt("retained_successes") <= r.optInt("cold_successes") || r.optInt("retained_successes") <= r.optInt("ablation_successes"))
            assertFalse("repeated uses are not attributed acquisition", r.optBoolean("attributed_learning_gain"))
    }

    @Test fun aScrollPrerequisiteMustMeasureMacroGainSeparatelyFromCounterfactualRegression() {
        val r = experiment().optObject("pagination_frontier")
        assertNotNull("the saturated primitive controls need a separate acquisition frontier", r)
        assertEquals(0, r!!.optInt("cold_successes", -1))
        assertEquals(32, r.optInt("retained_successes", -1))
        assertEquals(0, r.optInt("ablation_successes", -1))
        assertEquals(0, r.optInt("evaluation_teacher_calls", -1))
        assertTrue(r.optBoolean("negative_controls_passed"))
        assertTrue(r.optBoolean("disk_reload_verified"))
        assertTrue(r.optBoolean("frozen_memory_unchanged"))
        assertTrue(r.optBoolean("retained_macro_gain"))
        assertEquals(32, r.optInt("counterfactual_cold_successes", -1))
        assertEquals(0, r.optInt("click_only_demo_successes", -1))
        assertEquals(0, r.optInt("reversed_demo_successes", -1))
        assertEquals(0, r.optInt("beyond_trained_distance_successes", -1))
        if (r.optInt("counterfactual_regressions") > 0)
            assertFalse("unconditional macro replay must not become a broader learning claim", r.optBoolean("attributed_learning_gain"))
    }
}
