package com.appgate.brain.planner

/** Explicit, persistent opt-in; saved provider settings are never rewritten. */
enum class TeacherMode {
    CAPPED, REGULAR, SMARTER;

    fun apply(config: PlannerConfig): PlannerConfig {
        if (this == CAPPED || !TemporaryTeacher.eligible(config)) return config
        return if (this == SMARTER) config.copy(model = "gpt-5.4-2026-03-05", reasoningEffort = "medium",
            readTimeoutMs = 90_000, minimumOutputTokens = 4000)
        else config.copy(model = "gpt-5.4-mini-2026-03-17", reasoningEffort = "none")
    }

    companion object {
        fun selected(regular: Boolean, smarter: Boolean): TeacherMode = when {
            smarter -> SMARTER
            regular -> REGULAR
            else -> CAPPED
        }
    }
}
