# Nonmodal banner Implementation Plan
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
**Goal:** Preserve primary listing perception beneath nonmodal docked banners on short screens.
**Architecture:** Minimal generic dialogElements classification repair in content.js; real Chromium snapshots consumed by existing core fixture validation; add synthetic diagnostics coverage without frozen exam edits.
**Tech Stack:** JavaScript, Chromium, Kotlin/JUnit, existing Gradle/Android pipeline.
**Spec:** ../specs/2026-11-21-nonmodal-banner-design.md
## Global Constraints
Preserve explicit dialogs, auth/challenge, signing/workflows, private data, all existing timers/uploader/review. No version edits. No physical installation.
## Review Focus
- Actual modal with same geometry remains dialog.
- Full-screen role=region overlay remains dialog.
- Untyped fixed overlay remains dialog.
- Banner with nested explicit dialog preserves dialog ownership.
- Main detail and recommendations must remain distinguishable at short and tall viewports.
## Task1: browser perception regression
- [ ] Add synthetic browser fixture/harness using bottom role=region panel with safe links/close, listing main plus recommendation cards. Compare393x373 and393x915. Assert detailHint, actual core DETAIL/detail text and region ownership. Add all five negative cases.
- [ ] Run browser test before implementation and confirm short-viewport false-dialog failure.
- [ ] Implement bounded docked nonmodal landmark exclusion only in inferred-overlay scan; explicit semantic modal remains authoritative.
- [ ] Run focused harness/core tests, then full core/app/runner/receiver/browser/Android fixtures/build/lint and City clinics.
## Task2: verified integration
- [ ] Obtain fresh independent branch review, fix important findings test-first, rerun required gates.
- [ ] Commit scoped source; invoke unchanged globally locked stable release controller with expected old stable head d316 and exact review attestation; verify all source CI and signed artifact.
- [ ] Deploy City and emulator through existing reviewed transactions only after source/release verification; preserve memory/timers and verify report retrieval and restart proof. Coordinate existing review. Report physical result unproven.
