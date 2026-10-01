package com.appgate.brain.planner

/** Explicit, expiring trial. Never rewrites saved provider, model, endpoint or credentials. */
object TemporaryTeacher {
    const val DURATION_MS = 24 * 60 * 60_000L
    fun eligible(config: PlannerConfig): Boolean = config.provider == PlannerProvider.OPENAI &&
        config.resolvedEndpoint == "https://api.openai.com/v1/responses"

    fun apply(config: PlannerConfig, until: Long, now: Long): PlannerConfig {
        if (!eligible(config) || until <= now || until - now > DURATION_MS) return config
        return config.copy(model = "gpt-5.4-2026-03-05", reasoningEffort = "low",
            readTimeoutMs = 90_000, minimumOutputTokens = 4000)
    }
}
