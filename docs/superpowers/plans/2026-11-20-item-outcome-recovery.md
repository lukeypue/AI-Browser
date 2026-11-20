# Delayed item outcome recovery implementation plan

> For agentic workers: use superpowers:executing-plans task-by-task.

**Goal:** observe delayed listing outcomes before retrying a click, preserving strict verification.
**Architecture:** existing BrainEngine/Executor/SpsParser/Verifier; bounded observation-only retry, synthetic City clinic.
**Tech stack:** Kotlin/JUnit, existing browser harness, Gradle17, Python release guards.
**Spec:** docs/superpowers/specs/2026-11-20-item-outcome-recovery-design.md

## Global constraints
No secrets/signing/workflow/version changes, no phone installation, preserve running services and human-only boundaries; source709e6a9 base; without Claude.

## Review focus
- Genuine dialog must never earn item success.
- Human or offsite snapshots cannot disappear on later reads.
- Cancellation and bounded read count prevent new actions.
- Non-item actions retain their existing policy.
- Reloaded memory and held procedures retain their semantics.

## Task1: bounded item observation
- [ ] Add DelayedItemOutcomeTest reproducing first DIALOG then DETAIL and BUSY/RESULTS/DIALOG/DETAIL; verify RED.
- [ ] Add two capped observation-only rechecks to actual listing click path; check host, human, stop before continuing; never relax postconditions.
- [ ] Add negative overlays, human first/later, offsite, cancellation, bounded count tests; run full core suite.

## Task2: City experiment and release
- [ ] Add itemOutcomeClinic lab task using synthetic wire observations and actual persisted engine memory; no frozen exam/schema changes.
- [ ] Run full checks and actual City preflight.
- [ ] Obtain independent whole-change review, fix findings, repeat affected gates.
- [ ] Commit and normal fast-forward verified stable pipeline; verify downloaded signed APK/source/hash.
- [ ] Safely sync existing City, verify completed report retrieval, coordinate existing review. Keep native cycle deployment honest and preserve all existing timers.
