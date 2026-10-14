# Phone Site Label Verification Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the existing privacy-safe KSL label for the supported KSL Cars host, verify full checks, and publish through the existing stable pipeline.

**Architecture:** Add only `cars.ksl.com` to the exporter host allowlist using the receiver's existing `KSL` enum. No receiver schema, phone pairing, synthetic engine, human boundary, signing or workflow changes.

**Tech Stack:** Kotlin/JUnit, Python unittest, Node browser harness, existing Gradle/GitHub CI.

**Spec:** Root and ai-browser/AGENTS.md; existing phone diagnostics plan; user instruction to finish verification and automatic publishing.

## Global Constraints

- Never read secrets or change signing configuration.
- Preserve exact host allowlisting and receiver enum compatibility; arbitrary hosts stay UNKNOWN.
- Preserve all task budgets, synthetic fixtures/oracles and human-only boundaries.
- Run full CI on a review branch before a normal expected-head fast-forward to the app branch.
- Release metadata is allocated through ai-browser-release.json and the existing preparation pipeline; do not edit Android version declarations directly.
- Do not claim external AI participation without a supported authorized connection.

## Review Focus

- KSL Cars events retain an existing receiver-supported enum.
- Existing KSL Classifieds mapping remains KSL.
- Lookalike and arbitrary hosts remain UNKNOWN without raw-host leakage.
- Case-insensitive mapping retains existing exporter behavior.
- Event schema, counts, timestamp and receipt hashing remain unchanged.

### Task 1: Host label regression

**Files:** Modify ai-browser/brain-core/src/main/kotlin/com/appgate/brain/diagnostics/PhoneDiagnostics.kt; test ai-browser/brain-core/src/test/kotlin/com/appgate/brain/diagnostics/PhoneDiagnosticsTest.kt.

**Interfaces:** Existing PhoneReportExporter.export returns JsonObject, containing events with allowlisted site enums; no interface change.

- [ ] Add a test exporting cars.ksl.com and expecting KSL, plus case, classifieds and lookalike privacy controls.
- [ ] Run PhoneDiagnosticsTest and observe expected KSL/UNKNOWN assertion failure before production edit.
- [ ] Add cars.ksl.com to existing KSL entries only.
- [ ] Run full core suite, receiver/publisher tests and all browser harness tests.
- [ ] Commit and push review branch; verify core tests, Android unit tests, debug build and browser fixtures in existing CI.

### Task 2: Review and stable publication

**Files:** Existing release metadata only, if a new verified release needs version allocation.

**Interfaces:** Existing build workflow consumes ai-browser-release.json and publishes only on app branch, after its tests and stable signer verification.

- [ ] Obtain fresh Codex code review of the final diff; distinguish it from external providers.
- [ ] Allocate next release metadata via existing pipeline input, with expected app head.
- [ ] Verify updated review-branch CI and no concurrent app release, then fast-forward app branch normally.
- [ ] Verify every release step's metadata status (no signing-job logs), stable APK artifact and latest updater metadata.
- [ ] Record exact results and limitations; retain existing automatic review.
