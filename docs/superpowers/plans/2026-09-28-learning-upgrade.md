# Site Brain Learning Upgrade Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Steps use checkbox syntax.
**Goal:** Deliver a signed in-place Android update with bounded learning, verified reusable procedures, targeted lessons, and evaluation.
**Architecture:** Keep Claude's pure Kotlin brain, one Gecko controller, and existing typed executor. Move the release source to ai-browser/ and add focused policy components; memory remains local.
**Tech Stack:** Kotlin 2.2.10, Java 17, Gradle 9.3.1, JUnit 4, GeckoView, Android.
**Spec:** docs/superpowers/specs/2026-09-28-learning-upgrade.md

## Global Constraints
- Preserve original archive, stable signing identity, keys, cookies, and user memory.
- Do not bypass auth/challenges or permit unapproved external actions.
- Normal action execution is deterministic; use configured AI for unknown/repair.
- Verifier outcomes, not activity or planner confidence, authorize learned success.
- No secret reads or new paid integrations.
- User authorized continuous implementation and delivering the in-app update.

## Review Focus
- Process restart mid-program must not turn partial steps into verified skills (Task 3).
- Changed document or wrong-host response must not contaminate a task (Task 2).
- Two independent numeric values must remain independent (Task 3).
- Unsupported capabilities and human-only sites must not starve others (Task 4).
- Model unavailable, malformed plans, empty waits and repeated UI cycles remain bounded (Tasks 2/4).

### Task 1: Canonical build
Files: ai-browser/, .github/workflows/build.yml, .github/workflows/claude-v3-build.yml.
- [ ] Preserve archived baseline; extract canonical source; reproduce YAML failure.
- [ ] Build/test source directly; remove build-time string patches. Preserve signing steps exactly.
- [ ] Apply prior nullable compile, KSL entry, updater path and overnight wake-lock fixes to source.
- [ ] Add core-only Gradle settings for local tests; run baseline suite.
- [ ] Commit canonical source and build repair.

### Task 2: Progress supervision and task isolation
Files: brain-core/engine/BrainEngine.kt, ProgressSupervisor.kt, TaskPolicy.kt, Sessions.kt; model/Ledger.kt; app/service/BrainService.kt; browser/BrainSession.kt.
Interfaces: ProgressSupervisor observes distinct verified evidence; ledger stores terminal reasons and attempted/succeeded skills; EngineEvents.progress reports meaningful progress.
- [ ] Write and run failing tests: missing targets bounded, repeated verified navigation doesn't reset budget, wrong-host observation rejected, repeated planner plans yield.
- [ ] Add per-action/state failure memory, bounded repair, total decision budget, truthful terminal outcomes, host/document checks.
- [ ] Separate heartbeat from meaningful progress in service and diagnostics.
- [ ] Run full core suite; commit.

### Task 3: Verified skill learning and reuse
Files: engine/BrainEngine.kt, SkillRunner support; skills/SkillCompiler.kt; memory/SkillLibrary.kt; model/Skills.kt, Ledger.kt.
Interfaces: reusable skill selection by capability/host/preconditions, bound parameters and final postconditions; distinct parameter names.
- [ ] Write/run failing tests: drawer-open gives no filter credit, no-op programs fail, price/mileage distinct, learned procedure reused after reload, wrong-context rejected.
- [ ] Track program start/end and required verification; verify whole skill; compile only evidence-backed programs.
- [ ] Parameterize conditions with values; retain provenance and context; use applicable learned candidates before AI.
- [ ] Run full suite; commit.

### Task 4: Curriculum and teacher
Files: engine/Curriculum.kt, Sessions.kt; planner/Planner.kt; perception/SpsParser.kt; memory/Consolidation.kt; service/BrainService.kt.
Interfaces: persisted per-site lesson ordinal/block state; program outcome-based mastery; planner context with procedure bodies and selected failures.
- [ ] Write/run failing tests: unattempted lessons no penalty, unfinished skill no credit, blocked site rotates, all complete idles, later revealed role accepted, script-list churn preserves memory.
- [ ] Prioritize specific unfinished lessons, multi-filter lessons after basics, periodic checks, durable ordinal; maintain four-site fairness.
- [ ] Validate targets at execution; include relevant procedural memories and stable state-specific failures; fix planner state quota.
- [ ] Retain local structured lessons and consolidate verified programs without broad invalidation.
- [ ] Run core suite; commit.

### Task 5: Replay evaluation and UI status
Files: brain-core/src/test, docs/replay/, app/ui/LearningActivity.kt, store/DiagnosticsLog.kt.
- [ ] Add integrated cold/warm runs, rare-attribute UNKNOWN, repeat-failure and honest outcome cases.
- [ ] Add evaluation entry point/report with raw outcomes, calls, latency and reusable-skill counts.
- [ ] Show actual build identity and clear learning/blocked/partial status; preserve updater.
- [ ] Run tests and Android CI; commit.

### Task 6: Review and release
- [ ] Review whole diff independently, fix important findings with regressions.
- [ ] Allocate next version in release automation; build stable signed release without touching signing identity.
- [ ] Publish through existing authorized branch flow.
- [ ] Verify exact commit, APK signer/version, latest-version metadata and downloadable asset.
- [ ] Tell user install steps and device-only limitations.

## Scope ruling
Screenshot fallback is an optional future capability in the research, requiring a separately verified privacy-preserving browser adapter. Weight fine-tuning and unrestricted self-changing prompts are explicitly later projects. This release closes the concrete execution/learning/reuse gaps and provides offline evaluation; it does not claim general intelligence or measured live-site speedups.

