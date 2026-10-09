package com.appgate.brain.planner

import org.junit.Assert.*
import org.junit.Test

class TeacherModeTest {
    @Test fun premiumWinsWhenBothCheckboxesAreSelected() {
        val mode = TeacherMode.selected(regular = true, smarter = true)
        val config = PlannerConfig(apiKey = "fixture", model = "saved-model")
        assertEquals(TeacherMode.SMARTER, mode)
        val result = mode.apply(config)
        assertEquals("gpt-5.4-2026-03-05", result.resolvedModel)
        assertEquals("medium", result.reasoningEffort)
        assertEquals(4000, result.minimumOutputTokens)
        assertEquals("saved-model", config.model)
    }
    @Test fun regularUsesMiniAndOffPreservesTheSavedModel() {
        val config = PlannerConfig(apiKey = "fixture", model = "saved-model")
        assertEquals("gpt-5.4-mini-2026-03-17", TeacherMode.selected(true, false).apply(config).resolvedModel)
        assertEquals(config, TeacherMode.selected(false, false).apply(config))
    }
    @Test fun customEndpointsAndOtherProvidersKeepTheirConfiguredModels() {
        val custom = PlannerConfig(apiKey = "fixture", model = "custom", endpoint = "https://custom.example/responses")
        assertEquals(custom, TeacherMode.REGULAR.apply(custom))
        for (provider in PlannerProvider.values().filter { it != PlannerProvider.OPENAI }) {
            val config = PlannerConfig(provider, "fixture", "chosen")
            assertEquals(config, TeacherMode.SMARTER.apply(config))
        }
    }
}
