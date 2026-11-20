# Marketplace Learning District Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add an isolated, measurable learning district covering current marketplace trouble areas.

**Architecture:** A deterministic stateful synthetic renderer feeds the real engine. Scripted training writes verified procedural memory; independently forked cold, reloaded and ablated evaluations use no teacher.

**Tech Stack:** Existing pure Kotlin lab source set, JUnit 4, FileBrainStorage.

**Spec:** docs/superpowers/specs/2026-10-23-market-learning-district-design.md

## Global Constraints

Preserve legacy manifests, oracle, explicit budgets and human boundaries. Keep runtime/signing/workflow/deployment untouched. Synthetic hosts only. No external teacher participation claim. No automatic release.

## Review Focus

No-op actions cannot earn success; human-only observations permit zero actions; evaluation must not contaminate the frozen snapshot; skill removal must be real; disk reload cannot be labelled process restart.

### Task 1: District and independent evaluation

Files: add `MarketLearningDistrict.kt`, `MarketLearningDistrictTest.kt` and a standalone CLI entry point under the existing lab source set.

- [x] Add tests that fail while the district is absent: deterministic disjoint 56 training/336 evaluation cases, all seven capabilities and three evaluation variants, real no-op/human control outcomes, frozen memory independence and teacher-free comparisons.
- [x] Implement the stateful renderer and bounded lesson runner; retain independent physical truth and report actual outcome counts.
- [x] Persist/reload verified training memory, evaluate cold/retained/ablated forks and hash manifests and memory.
- [x] Add a separate 16/96 scroll-first acquisition screen, corrupt-demonstration controls, unseen element-tag axis, 32 beyond-distance probes and paired one-action counterfactuals. Keep macro gain distinct from a broader learning claim; a regression blocks the latter.
- [ ] Run focused tests, full core suite and frozen legacy manifest checks; run the standalone experiment and inspect all capability summaries.
- [ ] Review diff, publish only an isolated review branch/PR if checks pass, and hand off deployment without a stable release.
