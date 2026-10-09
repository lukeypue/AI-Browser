package com.appgate.brain.planner

import com.appgate.brain.memory.TeacherBudget
import com.appgate.brain.memory.TeacherBudgetExceeded
import com.appgate.brain.memory.TeacherBudgetSnapshot

/** Connects every actual provider request to the same durable local allowance. */
class BudgetedTeacher(
    private val budget: TeacherBudget,
    private val enabled: () -> Boolean,
    private val unrestricted: () -> Boolean = { false },
    private val changed: (TeacherBudgetSnapshot) -> Unit = {}
) : PlannerRequestObserver {
    private var reservation: Long? = null
    override fun beforeRequest(provider: PlannerProvider, model: String, maxOutputTokens: Int) {
        if (!enabled()) throw TeacherBudgetExceeded("Local-only mode is enabled")
        reservation = budget.reserve(provider.name, model, maxOutputTokens, unrestricted())
        notifyChanged()
    }
    override fun onUsage(usage: PlannerUsage) {
        val id = reservation ?: return
        budget.recordUsage(id, usage.inputTokens, usage.outputTokens, usage.cachedInputTokens, usage.reasoningTokens)
        notifyChanged()
    }

    /** Display/telemetry failures must not consume a reservation without sending, or discard paid output. */
    private fun notifyChanged() {
        try { changed(budget.snapshot(unrestricted())) } catch (_: Exception) { /* Accounting already succeeded. */ }
    }
}
