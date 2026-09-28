package com.appgate.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.appgate.brain.engine.SearchOutcome
import com.appgate.brain.goal.GoalParser
import com.appgate.brain.model.Goal
import com.appgate.brain.model.ItemVerdict
import com.appgate.brain.model.Verdict
import com.appgate.brain.model.VerifyStatus
import com.appgate.brain.profile.SiteProfiles
import com.appgate.tv.service.BrainService

/**
 * Deep Search: type what you want in plain English; the brain searches the sites that fit,
 * applies the filters it can, reads listings for the details it cannot filter, and returns
 * three ranked lists with evidence: verified, possible (unverified details) and near misses.
 */
class SearchActivity : ServiceBoundActivity() {
    private lateinit var queryBox: EditText
    private lateinit var status: TextView
    private lateinit var stepsView: TextView
    private lateinit var results: LinearLayout
    private lateinit var humanButton: android.widget.Button
    private val recentSteps = ArrayDeque<String>()
    private var goal: Goal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Deep Search"
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.bg) }
        val column = Ui.column(this)

        column.addView(Ui.text(this, "Deep Search", 24f, Ui.text, true))
        column.addView(Ui.text(this, "Say it like you would to a person. Example: Ford Expedition under 8k under 150k miles with a 3.73 axle", 13f, Ui.muted).apply { setPadding(0, 4, 0, 10) })
        queryBox = EditText(this).apply {
            hint = "What are you looking for?"
            setTextColor(Ui.text)
            setHintTextColor(Ui.muted)
            setSingleLine(false)
            minLines = 2
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Ui.card)
            setText(intent.getStringExtra("query").orEmpty())
        }
        column.addView(queryBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(Ui.row(this,
            Ui.button(this, "SEARCH") { startSearch() },
            Ui.button(this, "STOP") { service?.stopWork() }
        ))
        humanButton = Ui.button(this, "A site needs you — open the browser") { startActivity(Intent(this, BrowserActivity::class.java)) }.apply { visibility = View.GONE }
        column.addView(humanButton)
        status = Ui.text(this, "", 14f, Ui.accent).apply { setPadding(0, 10, 0, 4) }
        column.addView(status)
        stepsView = Ui.text(this, "", 11f, Ui.muted).apply { setPadding(0, 0, 0, 10) }
        column.addView(stepsView)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(results)
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onServiceReady(service: BrainService) {
        val outcome = service.currentOutcome()
        if (outcome != null) render(outcome)
        else BrainService.restoreLastSearch(this, service.memory)?.let { (g, ledgers) ->
            if (ledgers.isNotEmpty()) render(com.appgate.brain.engine.SearchMerge.outcome(g, ledgers))
        }
    }

    private fun startSearch() {
        val raw = queryBox.text.toString().trim()
        if (raw.isBlank()) { queryBox.error = "Tell AI Browser what you want to find"; return }
        val s = service ?: run { Toast.makeText(this, "The browser engine is still starting…", Toast.LENGTH_SHORT).show(); return }
        if (!s.state.engineReady) { Toast.makeText(this, "The browser engine is still starting…", Toast.LENGTH_SHORT).show(); return }
        val parsed = GoalParser.parse(raw)
        goal = parsed
        results.removeAllViews()
        recentSteps.clear()
        val preferred = getSharedPreferences("sources", MODE_PRIVATE).all.filter { it.value == true }.keys.map { it.removePrefix("always_") }
        val id = s.startSearch(parsed, preferred)
        if (id == null) { Toast.makeText(this, "The brain is busy; stop the current job first.", Toast.LENGTH_SHORT).show(); return }
        val summary = buildString {
            append("Understood: ").append(parsed.describe())
            if (parsed.warnings.isNotEmpty()) append("\n⚠ ").append(parsed.warnings.joinToString("\n⚠ "))
            append("\nSources: ").append(SiteProfiles.sourcesFor(parsed, preferred).joinToString { it.name })
        }
        status.text = summary
    }

    override fun onState(state: BrainService.State) {
        if (goal != null || state.mode == BrainService.Mode.SEARCHING || state.mode == BrainService.Mode.NEED_HUMAN || state.mode == BrainService.Mode.NEED_GRANT) {
            status.text = state.status
        }
        humanButton.visibility = if (state.mode == BrainService.Mode.NEED_HUMAN || state.mode == BrainService.Mode.NEED_GRANT) View.VISIBLE else View.GONE
        if (state.mode == BrainService.Mode.NEED_GRANT) humanButton.text = "A message is ready — review and approve"
        else humanButton.text = "${state.host.ifBlank { "A site" }} needs you — open the browser"
    }

    override fun onStep(description: String, status: VerifyStatus?) {
        recentSteps.addLast("${when (status) { VerifyStatus.VERIFIED -> "✓"; VerifyStatus.FAILED -> "✗"; else -> "…" }} $description")
        while (recentSteps.size > 6) recentSteps.removeFirst()
        stepsView.text = recentSteps.joinToString("\n")
    }

    override fun onSearchOutcome(outcome: SearchOutcome) { render(outcome) }

    private fun render(outcome: SearchOutcome) {
        results.removeAllViews()
        val merged = outcome.merged
        val goal = outcome.goal
        results.addView(Ui.text(this, "${merged.verified.size} verified · ${merged.partial.size} possible · ${merged.nearMiss.size} near misses · ${merged.inspected} listings read in full", 14f, Ui.muted, true).apply { setPadding(0, 8, 0, 8) })
        merged.notes.forEach { results.addView(Ui.text(this, "• $it", 12f, Ui.muted)) }
        fun section(title: String, color: Int, items: List<ItemVerdict>, explain: (ItemVerdict) -> String) {
            if (items.isEmpty()) return
            results.addView(Ui.text(this, title, 18f, color, true).apply { setPadding(0, 16, 0, 6) })
            items.take(40).forEach { v -> results.addView(card(goal, v, explain(v)), Ui.cardParams()) }
        }
        section("Verified matches", Ui.good, merged.verified) { "Every requirement confirmed" + (if (it.evidence.any { e -> e.method != "structured" }) " — " + it.evidence.filter { e -> e.method != "structured" }.joinToString("; ") { e -> "${e.key}: “${e.span}”" } else "") }
        section("Possible matches", Ui.warn, merged.partial) { v ->
            val unknown = goal.constraints.filter { c -> v.perConstraint[c.key] == Verdict.UNKNOWN }.joinToString { c -> c.value }
            "Main filters pass; not confirmed: $unknown" + (if (!v.inspectedDetail) " (listing not read yet)" else " (not mentioned in the description)")
        }
        section("Near misses", Ui.bad, merged.nearMiss) { v ->
            val violated = goal.hard.filter { c -> v.perConstraint[c.key] == Verdict.VIOLATED }.joinToString { c -> c.describe() }
            "Just outside: $violated"
        }
        if (merged.total == 0) results.addView(Ui.text(this, "No listings collected yet. If a site needed a sign-in, open the browser, finish it, and tap Continue.", 14f, Ui.warn).apply { setPadding(0, 14, 0, 14) })
    }

    private fun card(goal: Goal, v: ItemVerdict, explanation: String): LinearLayout {
        val c = Ui.card(this)
        c.addView(Ui.text(this, v.title, 16f, Ui.text, true))
        c.addView(Ui.text(this, listOfNotNull(Ui.money(v.price).takeIf { it.isNotBlank() }, v.mileage?.let { Ui.number(it) + " miles" }, v.year?.toString(), v.url?.let { com.appgate.brain.perception.UrlPatterns.host(it) }).joinToString(" · "), 13f, Ui.muted))
        c.addView(Ui.text(this, explanation, 12f, Ui.accent).apply { setPadding(0, 4, 0, 6) })
        val url = v.url
        c.addView(Ui.row(this,
            Ui.button(this, "Open") { if (url != null) startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url)) },
            Ui.button(this, "Message seller") { if (url != null) askMessage(url, v) }
        ))
        return c
    }

    private fun askMessage(url: String, v: ItemVerdict) {
        val input = EditText(this).apply { setText("Hi, is this still available?"); setSingleLine(false); minLines = 2 }
        AlertDialog.Builder(this).setTitle("Message the seller")
            .setMessage("The brain opens the listing and fills this in for you. Nothing is sent until you approve it in the browser screen.")
            .setView(input)
            .setPositiveButton("Prepare") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) return@setPositiveButton
                service?.prepareMessage(url, text)
                startActivity(Intent(this, BrowserActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
