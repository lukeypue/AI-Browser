# Local-first Brain Implementation Plan

> **For agentic workers:** Use the test-driven-development and verification-before-completion workflows; independent workers own disjoint files and the root integrates/reviews the whole branch.

**Goal:** More verified local learning with fewer remote requests and a signed update.

**Architecture:** Pure Kotlin semantic policy, procedural memory and bounded local recovery execute through the existing verifier. Remote providers are explicit adapters with durable request accounting.

**Tech Stack:** Kotlin/JVM 17, Android/GeckoView, existing JSON implementation, JUnit4 and Node fixture tests.

**Spec:** `docs/superpowers/specs/2026-09-29-local-first-brain-design.md`

## Global constraints
- No secrets, page content or query values in new persisted learning/usage records.
- Preserve human-only and commit boundaries; no new Android permissions or signing changes.
- 24 teacher requests/rolling24h and 6/rolling hour by default; local-only means zero remote calls.
- Keep one-minute site rechecks and existing memory compatible.
- No claims of measured live performance from synthetic fixtures.

## Review focus
- Process restart and clock rollback must not reset the paid-call allowance.
- A popup or DOM replacement must not reuse a stale action effect/identity.
- Source-host success must not erase a failed target-host transfer.
- Absence on one page is not permanent absence from the site.
- Synthetic detail lessons must not consume model evidence calls.

## Task 1: Provider transport and usage
Files: `planner/PlannerClient.kt`, new `planner/PlannerUsage.kt`, `planner/PlannerClientTest.kt`.
Interfaces: preserve PlannerClient.complete; add optional structured usage callback and mockable transport. Add documented provider configuration for compatible chat, budget-independent usage events. Root supplies durable gating/wrapper.
- [ ] Test economical default, explicit model preservation, no hidden fallback, provider JSON shapes, usage extraction and sanitized failures (RED).
- [ ] Implement explicit adapters and callbacks; run focused tests (GREEN).
- [ ] Document provider selection/research; root reviews and commits.

## Task 2: Portable verified memory
Files: `skills/SkillCompiler.kt`, `memory/SkillLibrary.kt`, new transfer tests.
Interfaces: preserve reusable(sps,capability,params,ledger): Skill? and compileFromPlanner; add safe probationary transfer to existing retrieval.
- [ ] Test held-out host reuse, incompatible parameters/entry role, target failure suppression, nontransferable actions and sanitized persistence (RED).
- [ ] Implement host-independent canonical compilation and conservative transfer gates (GREEN).
- [ ] Run relevant memory/replay tests; root reviews and commits.

## Task 3: Opportunity-driven lessons
Files: `engine/Curriculum.kt`, `engine/Sessions.kt`, `model/SiteModel.kt`, optional new `engine/LearningOpportunities.kt`, focused lesson tests.
Interfaces: Curriculum.observe(site,sps,now), existing nextGoal/nextLesson compatible defaults; per-lesson retry timing stored without content. Root calls observation from engine and skips synthetic remote evidence.
- [ ] Test missing Next, present alternative facet, new layout retry, preserved completed lessons and bounded one-minute recheck behavior (RED).
- [ ] Implement observations, selection and deferred lessons without false completion (GREEN).
- [ ] Run session/curriculum tests; root reviews and commits.

## Task 4: Local recovery and fresh grounding
Files: new `engine/LocalRecoveryPlanner.kt`, `engine/StepGrounder.kt`, recovery tests. Root owns `engine/BrainEngine.kt` integration.
Interfaces: LocalRecoveryPlanner.propose(ledger,sps,site,capability): local recipe containing steps, params, postconditions or null; no mutation/network. StepGrounder.ground adds defaulted excludedIds set.
- [ ] Test exact live facet choice/range, safe opener/apply, ambiguous/missing/commit rejection and fresh alternate effects (RED).
- [ ] Implement bounded recipes and fresh grounding (GREEN).
- [ ] Root integrates before remote repair, bounds attempts, preserves verifier/compiler evidence; engine regressions pass.

## Task 5: Durable limits and Android controls
Files: new memory request-budget implementation/tests, `Memory.kt`, `BrainEngine.kt`, `BrainService.kt`, `MainActivity.kt`, `LearningActivity.kt`, settings source.
- [ ] Test 24/day + 6/hour reservation across restart, clock rollback, request failures, local-only and no training evidence calls (RED).
- [ ] Implement persistent budget and wrapper; wire explicit settings/provider/model and usage display (GREEN).
- [ ] Verify counters are content-free and no implicit provider/model escalation.

## Task 6: Evaluation, review and release
Files: held-out replay tests, `docs/research/2026-09-29-brain-research.md`, release descriptor owned by release automation.
- [ ] Run whole JVM suite with real JUnit, extractor tests, held-out transfer and repair comparisons; report task success + actions + teacher calls.
- [ ] Independent whole-branch review, fix important findings with regression tests.
- [ ] Commit and push using connected GitHub; run canonical Android CI; verify signing and published version JSON/APK.
- [ ] Deliver update link, actual test evidence, API recommendation and clear live-device limits.
