# Site Brain Lab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone safe website-learning runner that produces reusable sanitized Site Knowledge Packs and integrate those packs plus detailed learning-log export into AI Browser Android.

**Architecture:** A JVM/Kotlin Site Brain Lab reuses pure semantic concepts from the Android Site Brain but has no Android dependency. It explores public pages through an adapter interface, checkpoints verified graph knowledge, calculates structural coverage, and emits JSON packs. Android imports/merges packs while retaining stronger live evidence and keeps private/authenticated state local.

**Tech Stack:** Kotlin/JVM, Gradle, JUnit 4, JSON serialization using existing project-compatible facilities, Android WebView for the app-side consumer, GitHub Actions for deterministic tests/builds.

**Spec:** `docs/superpowers/specs/2026-09-13-site-brain-lab-design.md`

## Global Constraints

- Android remains the only end-user platform.
- Automatic actions are safe, reversible navigation/search/read actions only.
- No CAPTCHA/MFA/security/anti-bot bypass.
- No automatic purchase, bid, checkout, seller messaging, posting, deletion, account changes or sensitive submissions.
- Shared packs/logs contain no passwords, cookies, tokens, phone/email, private messages, payment data or CAPTCHA answers.
- Structural coverage measures discovered safe interface functions, not every content item.
- Login-heavy/private learning remains on-device.
- GitHub remains the deterministic build/test path; crawler runtime stays portable to a later continuously running service.

---

### Task 1: Shared Knowledge Pack Contract

**Files:**
- Create: `sitebrain-lab/src/main/kotlin/com/appgate/sitebrainlab/KnowledgePack.kt`
- Create: `sitebrain-lab/src/test/kotlin/com/appgate/sitebrainlab/KnowledgePackTest.kt`
- Create/Modify: `sitebrain-lab/build.gradle`
- Modify: `settings.gradle`

**Interfaces:**
- Produces: `SiteKnowledgePack`, `PackNode`, `PackTransition`, `PackBoundary`, `KnowledgePackCodec.encode/decode`.
- Consumers: Lab checkpoint/export tasks and Android pack importer.

- [ ] Write a failing round-trip test using a KSL pack with one RESULTS node, one verified SEARCH transition and one unresolved boundary.
- [ ] Run the Lab unit test and verify it fails because the pack types/codec do not exist.
- [ ] Implement immutable pack models with `schemaVersion`, `domain`, `hostAliases`, `generatedAtEpochMs`, `coveragePercent`, `readiness`, nodes, transitions and boundaries plus deterministic JSON encoding/decoding.
- [ ] Run the round-trip test and verify exact semantic fields survive encoding/decoding.
- [ ] Commit with `feat: add site knowledge pack contract`.

### Task 2: Lab Safety and Sanitization

**Files:**
- Create: `sitebrain-lab/src/main/kotlin/com/appgate/sitebrainlab/LabSafety.kt`
- Create: `sitebrain-lab/src/test/kotlin/com/appgate/sitebrainlab/LabSafetyTest.kt`

**Interfaces:**
- Produces: `LabSafety.classify(label, role, href): LabActionSafety` and `PackSanitizer.sanitize(pack): SiteKnowledgePack`.
- Consumes: Task 1 pack models.

- [ ] Write failing tests proving search/filter/category/detail are SAFE while buy/bid/checkout/message/post/delete/follow/account/security actions are CONSEQUENTIAL or PROTECTED.
- [ ] Add a failing sanitization test containing cookie/token/email/phone/private-message-like metadata and assert none survives the sanitized output.
- [ ] Run tests and confirm RED.
- [ ] Implement conservative semantic classification and allowlisted pack fields; unknown actions are not executable.
- [ ] Run tests and confirm GREEN.
- [ ] Commit with `feat: enforce lab safety and pack sanitization`.

### Task 3: Resumable Exploration Engine

**Files:**
- Create: `sitebrain-lab/src/main/kotlin/com/appgate/sitebrainlab/Explorer.kt`
- Create: `sitebrain-lab/src/test/kotlin/com/appgate/sitebrainlab/ExplorerTest.kt`

**Interfaces:**
- Produces: `ExplorerState`, `ExplorerBudget`, `ExplorerDecision`, `SiteExplorer.next(state, observation)`.
- Observation contains semantic page fingerprint, page type, safe controls, protected boundary flag and transition evidence.

- [ ] Write failing fixture tests for HOME -> CATEGORY -> RESULTS -> DETAIL breadth-first traversal, duplicate-state suppression and a 60-action/120-page/15-minute default budget.
- [ ] Add failing tests proving CAPTCHA/login boundaries are recorded and exploration continues with other frontier branches.
- [ ] Add a failing stagnation test where three equivalent transitions terminate that branch.
- [ ] Run tests and confirm RED.
- [ ] Implement deterministic BFS frontier, visited fingerprints, unresolved frontier, budget accounting, protected-boundary recording and stagnation counters.
- [ ] Run tests and confirm GREEN.
- [ ] Commit with `feat: add resumable safe site explorer`.

### Task 4: Coverage, Checkpoints and Pack Builder

**Files:**
- Create: `sitebrain-lab/src/main/kotlin/com/appgate/sitebrainlab/Coverage.kt`
- Create: `sitebrain-lab/src/main/kotlin/com/appgate/sitebrainlab/Checkpoint.kt`
- Create: `sitebrain-lab/src/test/kotlin/com/appgate/sitebrainlab/CoverageTest.kt`

**Interfaces:**
- Produces: `CoverageEngine.calculate(state): CoverageReport`, `CheckpointCodec`, `PackBuilder.build(state, report)`.
- Consumes: Tasks 1 and 3.

- [ ] Write failing tests where discovered search/category/filter/results/detail/pagination functions become 100% only when each is classified as verified or explicitly unresolved/protected.
- [ ] Write a failing checkpoint-resume test that serializes an unfinished frontier and resumes without revisiting verified nodes.
- [ ] Run tests and confirm RED.
- [ ] Implement weighted structural coverage, checkpoint serialization and sanitized pack building.
- [ ] Run tests and confirm GREEN.
- [ ] Commit with `feat: add lab coverage checkpoints and pack builder`.

### Task 5: Browser Adapter Boundary and First Site Configurations

**Files:**
- Create: `sitebrain-lab/src/main/kotlin/com/appgate/sitebrainlab/BrowserAdapter.kt`
- Create: `sitebrain-lab/src/main/kotlin/com/appgate/sitebrainlab/SiteCatalog.kt`
- Create: `sitebrain-lab/src/test/kotlin/com/appgate/sitebrainlab/SiteCatalogTest.kt`

**Interfaces:**
- Produces: `BrowserAdapter.observe`, `BrowserAdapter.executeSafe`, `SiteLearningConfig` and catalog entries for KSL Cars, eBay, Craigslist, AutoTrader, Cars.com, CarGurus, Edmunds, TrueCar, CarMax, OfferUp and Facebook public surfaces.
- The adapter is deliberately replaceable so a continuous browser runtime can be attached without changing Explorer/pack logic.

- [ ] Write failing tests asserting each priority domain has HTTPS public entry points, allowed host aliases and no credentials.
- [ ] Write a failing adapter contract test proving CONSEQUENTIAL/PROTECTED actions cannot be passed to execution.
- [ ] Run tests and confirm RED.
- [ ] Implement the adapter interface, safe execution guard and domain catalog; Facebook config explicitly marks authenticated coverage as device-only.
- [ ] Run tests and confirm GREEN.
- [ ] Commit with `feat: define lab browser adapter and priority sites`.

### Task 6: Android Pack Import and Merge

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/SiteKnowledgePackImporter.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/SiteKnowledgePackImporterTest.kt`
- Modify: `app/src/main/java/com/appgate/tv/sitebrain/SiteBrainStore.kt`

**Interfaces:**
- Produces: `SiteKnowledgePackImporter.merge(remotePack, localBrain)`.
- Rule: stronger/newer verified local evidence wins over weaker remote hints; incompatible schema is rejected without damaging local state.

- [ ] Write failing tests for new-pack import, local-verified precedence, newer verified remote transition merge and incompatible schema rejection.
- [ ] Run tests and confirm RED.
- [ ] Implement Android-side pack DTO/decoder and deterministic merge into `SiteBrainState`.
- [ ] Run tests and confirm GREEN.
- [ ] Commit with `feat: import shared site knowledge packs`.

### Task 7: Continuous Android Learning and In-App Navigation

**Files:**
- Modify: `app/src/main/java/com/appgate/tv/BrowserActivity.kt`
- Create/Modify: `app/src/main/java/com/appgate/tv/sitebrain/WebNavigationPolicy.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/WebNavigationPolicyTest.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/ContinuousLearningPolicyTest.kt`

**Interfaces:**
- Produces: continuous per-site exploration budget and URL decisions `IN_WEBVIEW`, `SAFE_WEB_FALLBACK`, `BLOCK_APP_HANDOFF`.

- [ ] Keep the existing failing navigation-policy test for ordinary eBay HTTPS, eBay `intent://` fallback and app-only schemes.
- [ ] Write a failing continuous-learning test asserting more than one safe action is allowed per site and default learning budget is finite.
- [ ] Run tests and confirm RED on current v5.1 behavior.
- [ ] Implement URL interception so normal HTTPS stays in WebView, safe HTTPS browser fallback is extracted from supported intents, and app-only handoffs are blocked during autonomous exploration.
- [ ] Replace the one-action governor with the finite continuous-learning policy while preserving challenge pauses and consequential-action blocking.
- [ ] Run tests and confirm GREEN.
- [ ] Commit with `feat: keep autonomous learning inside AI Browser`.

### Task 8: Parser Cleanup and Privacy-Safe Learning Reports

**Files:**
- Modify: `app/src/main/java/com/appgate/tv/SearchIntent.kt`
- Modify: `app/src/test/java/com/appgate/tv/SearchIntentTest.kt`
- Create: `app/src/main/java/com/appgate/tv/sitebrain/LearningReport.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/LearningReportTest.kt`
- Modify: `app/src/main/java/com/appgate/tv/MainActivity.kt`

**Interfaces:**
- Produces: cleaned `requiredTerms`; `LearningReportWriter` and Android share intent for a single JSON report.

- [ ] Add failing parser tests for `expedition under 8k with 3.73 axle and under 150k miles` and punctuation variants; expected required term is `3.73 axle` with no dangling conjunction.
- [ ] Add failing report tests proving URLs/page types/actions/coverage/failures survive while cookies/tokens/email/phone/private text do not.
- [ ] Run tests and confirm RED.
- [ ] Implement conjunction cleanup and privacy-safe event/report models.
- [ ] Add a `SHARE LEARNING DATA` button using Android's share sheet for the generated JSON file; no automatic upload in this milestone.
- [ ] Run tests and confirm GREEN.
- [ ] Commit with `feat: export privacy-safe site learning reports`.

### Task 9: Version, Full Verification and APK

**Files:**
- Modify: `app/build.gradle`
- Modify: `README.md` or existing release notes file if present.

**Interfaces:**
- Produces: next alpha APK and documented Site Brain Lab usage/build commands.

- [ ] Set next versionCode and versionName to identify the Site Brain Lab/continuous-learning alpha.
- [ ] Run Lab unit tests with `./gradlew :sitebrain-lab:test` and require PASS.
- [ ] Run Android unit tests with `./gradlew testDebugUnitTest` and require PASS.
- [ ] Run Android APK build with `./gradlew assembleDebug` and require PASS.
- [ ] Inspect CI artifact metadata and ensure it comes from the exact latest commit SHA.
- [ ] Commit with `release: build site brain lab alpha`.
