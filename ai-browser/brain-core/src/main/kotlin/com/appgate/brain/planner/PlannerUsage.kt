package com.appgate.brain.planner

/** Content-free usage reported by a provider, not an estimate of currency cost. */
data class PlannerUsage(
    val provider: PlannerProvider,
    val model: String,
    /** Total input, including cached reads and Anthropic cache creation. */
    val inputTokens: Long,
    /** Total output; reasoningTokens is a subset, not an additional charge. */
    val outputTokens: Long,
    val cachedInputTokens: Long = 0,
    val reasoningTokens: Long = 0
)

interface PlannerRequestObserver {
    /** Reserve durable allowance here. Throwing prevents the request; exceptions propagate unchanged. */
    fun beforeRequest(provider: PlannerProvider, model: String, maxOutputTokens: Int) {}

    /** Called when usage is present, including responses whose output is subsequently rejected. */
    fun onUsage(usage: PlannerUsage) {}
}
