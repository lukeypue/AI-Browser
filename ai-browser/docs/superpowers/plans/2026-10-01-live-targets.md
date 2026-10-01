# Live targets and temporary stronger teacher implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task. Steps use checkbox syntax.

**Goal:** Restore useful learning from the live sites and offer a bounded stronger teacher trial.
**Architecture:** Keep the existing semantic perception, verifier and local-first engine. Add target restrictions, a prerequisite eligibility rule and a pure temporary teacher configuration policy.
**Tech Stack:** Kotlin brain-core, Android, GeckoView extension, JUnit, Node/Playwright, existing GitHub Actions.
**Spec:** ai-browser/docs/LEARNING_REPAIR_7_2_3.md

## Global Constraints
- Preserve signing, credentials, human-only boundaries, request limits and saved model settings.
- No persistent page text or query values in new diagnostics.
- No paid model requests during tests.

## Review Focus
- A href-less location span in a priced card remains non-listing.
- Visible accordion opener works without opening/dismissing Location.
- Stale ABSENT records cannot strand unfinished lessons on SEARCH; cooling lessons stay cooling.
- Off-capability teacher programs cannot execute or earn lessons.
- Expiry, custom endpoints and non-OpenAI providers preserve original config.

### Task 1: Targets, prerequisites and planner contracts
**Files:** RoleClassifier.kt, LearningOpportunities.kt, Curriculum.kt, Planner.kt, BrainEngine.kt, SemanticDiagnostics.kt, content.js and regression tests.
**Interfaces:** Existing raw observations -> SemanticPageState; Planner.proposeProgram adds optional requested capability.
- [x] Write regressions for the five corresponding target/contract scenarios.
- [x] Run targeted Kotlin tests and confirm expected failures.
- [x] Restrict listing roles; allow named accordion helpers on results; enforce safe global opener targeting; admit bounded search prerequisites; validate teacher capability.
- [x] Run brain-core suite and document-boundary suite; commit passing changes.

### Task 2: Temporary stronger teacher
**Files:** TemporaryTeacher.kt (new), PlannerClient.kt, PlannerKeyStore.kt, LearningActivity.kt, policy/wire tests.
**Interfaces:** TemporaryTeacher.apply(config, until, now) -> PlannerConfig; settings persist expiry only.
- [x] Test expiry, providers/endpoints, unchanged saved config, reasoning and token ceiling sent to fake transport.
- [x] Confirm failure, implement pure policy plus explicit dashboard control.
- [x] Run core suite, update release version to 88 / 7.2.3-site-brain (bridge unchanged: no extension code changes); commit.

### Task 3: Review and release
- [x] One independent whole-branch review; fix important findings with reproductions.
- [ ] Push feature via GitHub connector; verify CI core/browser/Android gates.
- [ ] Publish authorized release branch via existing workflow; verify live updater version and commit.
