# Site Brain v5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first working Android Site Brain foundation so AI Browser can safely explore websites broadly, remember semantic site structure, reuse learned paths, verify outcomes, and search more than one path per site.

**Architecture:** Keep the current Android WebView app, but split site understanding into focused components: semantic page snapshots, safe action classification, persistent site graphs, bounded breadth-first exploration, outcome verification, auto-repair hooks, and a Central Search Brain that consumes learned paths. The existing generic anchor/result scraper remains a low-confidence fallback only and must never label page-level junk as a verified listing.

**Tech Stack:** Android/Kotlin, WebView JavaScript bridge, SharedPreferences/JSON for v5 local persistence, JUnit 4, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-13-site-brain-design.md`

## Global Constraints

- Android only; no Windows dependency.
- Preserve legitimate WebView cookies/session continuity when the user opts in.
- Never bypass CAPTCHA, MFA, security checks, anti-bot systems, login protections, or site access controls.
- Pause for legitimate human verification and resume afterward.
- Never auto-trigger consequential controls such as Buy, Bid, Checkout, Message, Post, Delete, Follow, payment, or account changes.
- Never store passwords, cookies, session tokens, payment data, private messages, CAPTCHA answers, or sensitive account data in Site Brain knowledge.
- Exploration must be finite through explicit page/action/time/revisit budgets.
- Hard requirements may only be marked verified when evidence exists.
- GitHub Actions remains the build/test path.

---

### Task 1: Semantic Site Brain Data Model

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/SiteBrainModels.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/SiteBrainModelsTest.kt`

**Interfaces:**
- Produces: `PageType`, `ReadinessLevel`, `ActionKind`, `SafetyClass`, `SemanticElement`, `PageSnapshot`, `SiteNode`, `SiteEdge`, `SiteBrainState`.

- [ ] **Step 1: Write failing model tests**

```kotlin
@Test fun readinessOrdersFromUnmappedToDeepReady() {
    assertTrue(ReadinessLevel.DEEP_SEARCH_READY.ordinal > ReadinessLevel.SEARCH_READY.ordinal)
}

@Test fun siteStateDefaultsToUnmapped() {
    val state = SiteBrainState.empty("example.com")
    assertEquals(ReadinessLevel.UNMAPPED, state.readiness)
    assertTrue(state.nodes.isEmpty())
}
```

- [ ] **Step 2: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*SiteBrainModelsTest*'`
Expected: FAIL because the Site Brain model types do not exist.

- [ ] **Step 3: Implement the model types**

Use immutable Kotlin data classes. `PageSnapshot` must contain URL, host, route signature, title, visible text summary, headings, semantic elements, page type guess, login/challenge flags, and fingerprint. `SiteEdge` must hold semantic intent, safety class, locator hints, expected destination/page type, observed postcondition, confidence, success/failure counters, and last verified timestamp.

- [ ] **Step 4: Re-run tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests '*SiteBrainModelsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/appgate/tv/sitebrain/SiteBrainModels.kt app/src/test/java/com/appgate/tv/sitebrain/SiteBrainModelsTest.kt
git commit -m "feat: add Site Brain semantic data model"
```

### Task 2: Safe Action Classifier

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/SafeActionClassifier.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/SafeActionClassifierTest.kt`

**Interfaces:**
- Consumes: `SemanticElement`, `ActionKind`, `SafetyClass`.
- Produces: `SafeActionClassifier.classify(element): SafetyClass` and `SafeActionClassifier.inferActionKind(element): ActionKind`.

- [ ] **Step 1: Write failing safety tests**

```kotlin
@Test fun searchAndCategoryControlsAreSafe() {
    assertEquals(SafetyClass.SAFE, classifier.classify(element("Search")))
    assertEquals(SafetyClass.SAFE, classifier.classify(element("Vacation Rentals")))
}

@Test fun purchaseAndMessagingControlsAreConsequential() {
    listOf("Buy now", "Checkout", "Send Message", "Delete", "Post Listing", "Follow")
        .forEach { label -> assertEquals(SafetyClass.CONSEQUENTIAL, classifier.classify(element(label))) }
}
```

- [ ] **Step 2: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*SafeActionClassifierTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement conservative classification**

Classify search, navigation, category, filter, sort, pagination, expand/read-only, tab, and back-like controls as safe when no consequential signal exists. Treat payment, purchase, bid, message/contact, submit/post/upload, delete/remove, follow/subscribe, account/profile/security, agreement acceptance, and unknown form submission as consequential or blocked-by-default.

- [ ] **Step 4: Run tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests '*SafeActionClassifierTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/appgate/tv/sitebrain/SafeActionClassifier.kt app/src/test/java/com/appgate/tv/sitebrain/SafeActionClassifierTest.kt
git commit -m "feat: classify safe and consequential site actions"
```

### Task 3: Semantic Page Snapshot + Fingerprinting

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/SemanticPageSnapshot.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/SemanticPageSnapshotTest.kt`

**Interfaces:**
- Produces: `SemanticPageSnapshot.javascript(): String`, `SemanticPageSnapshot.parse(json): PageSnapshot`, `PageFingerprint.compute(snapshot): String`.

- [ ] **Step 1: Write failing parser/fingerprint tests**

Use saved JSON fixtures representing a category page and the same page with cosmetic text differences. Assert stable route signatures and duplicate-state fingerprints for semantically equivalent pages, while a result-list page produces a different fingerprint.

- [ ] **Step 2: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*SemanticPageSnapshotTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement semantic DOM extraction**

JavaScript must collect visible headings, links, buttons, inputs, select controls, ARIA labels/roles, hrefs, selected/disabled states, nearby text, pagination hints, scroll information, login/challenge signals, and a compact visible-text summary. Do not collect password values, cookies, localStorage/sessionStorage, private form values, or hidden authentication data.

- [ ] **Step 4: Implement normalized fingerprinting**

Fingerprint from host + normalized route signature + page-type signals + normalized headings + semantic control labels, not raw full HTML.

- [ ] **Step 5: Run tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests '*SemanticPageSnapshotTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/appgate/tv/sitebrain/SemanticPageSnapshot.kt app/src/test/java/com/appgate/tv/sitebrain/SemanticPageSnapshotTest.kt
git commit -m "feat: add semantic page snapshots and fingerprints"
```

### Task 4: Persistent Site Graph Repository

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/SiteBrainRepository.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/SiteBrainRepositoryTest.kt`

**Interfaces:**
- Produces: `load(host): SiteBrainState`, `save(state)`, `recordNode(snapshot)`, `recordTransition(host, fromFingerprint, edge, toFingerprint)`, `markSuccess(...)`, `markFailure(...)`.

- [ ] **Step 1: Write failing persistence tests**

Use an in-memory preferences fake. Save a graph with two nodes and one edge, reload it, and assert semantic intent, confidence, counters, readiness, and locator hints survive serialization.

- [ ] **Step 2: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*SiteBrainRepositoryTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement JSON persistence**

Store generalized semantic site knowledge only. Explicitly exclude CookieManager data, form values, credentials, and page bodies containing private account content.

- [ ] **Step 4: Add readiness calculation**

`UNMAPPED`: no verified search/category path. `LEARNING`: graph exists but coverage is partial. `SEARCH_READY`: verified search/category -> result flow exists. `DEEP_SEARCH_READY`: verified result -> detail extraction plus alternate branch/filter evidence exists.

- [ ] **Step 5: Run tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests '*SiteBrainRepositoryTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/appgate/tv/sitebrain/SiteBrainRepository.kt app/src/test/java/com/appgate/tv/sitebrain/SiteBrainRepositoryTest.kt
git commit -m "feat: persist semantic Site Brain graphs"
```

### Task 5: Outcome Verifier and Auto-Repair Candidate Matching

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/OutcomeVerifier.kt`
- Create: `app/src/main/java/com/appgate/tv/sitebrain/ActionRepairer.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/OutcomeVerifierTest.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/ActionRepairerTest.kt`

**Interfaces:**
- Produces: `OutcomeVerifier.verify(before, action, after): VerificationResult`, `ActionRepairer.rankReplacementCandidates(expectedEdge, currentSnapshot): List<SemanticElement>`.

- [ ] **Step 1: Write failing verifier tests**

Cover route changes, filter-chip changes, result-list appearance, detail-page transitions, and no-op clicks. A click with no meaningful postcondition must not gain confidence.

- [ ] **Step 2: Write failing repair tests**

Create an old semantic action `MAX_PRICE_FILTER` with label `Max Price`; current page changes label to `Price up to`. Assert semantic matching ranks the new control above unrelated controls.

- [ ] **Step 3: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*OutcomeVerifierTest*' --tests '*ActionRepairerTest*'`
Expected: FAIL.

- [ ] **Step 4: Implement verification and semantic repair ranking**

Use route/page-type changes, heading changes, selected state, visible result/filter changes, and label/role/type similarity. Repair may suggest safe controls only; it must not execute consequential candidates.

- [ ] **Step 5: Run tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests '*OutcomeVerifierTest*' --tests '*ActionRepairerTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/appgate/tv/sitebrain/OutcomeVerifier.kt app/src/main/java/com/appgate/tv/sitebrain/ActionRepairer.kt app/src/test/java/com/appgate/tv/sitebrain/OutcomeVerifierTest.kt app/src/test/java/com/appgate/tv/sitebrain/ActionRepairerTest.kt
git commit -m "feat: verify outcomes and repair semantic actions"
```

### Task 6: Bounded Breadth-First Site Explorer

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/SiteExplorer.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/SiteExplorerTest.kt`

**Interfaces:**
- Produces: `ExplorerBudget`, `ExplorerDecision`, `SiteExplorer.nextAction(state, snapshot, budget): ExplorerDecision`.

- [ ] **Step 1: Write failing breadth-first tests**

Test that the Explorer prefers unexplored safe categories/search/filter controls, avoids duplicate fingerprints, never chooses consequential controls, and terminates after explicit action/page/revisit budgets.

- [ ] **Step 2: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*SiteExplorerTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement ranking and budgets**

Prioritize major navigation, category, search, filters, result/detail links, pagination, and related-category branches. Deprioritize repetitive utility/footer links. Stop when budget expires, no safe unexplored candidates remain, or a challenge requires human handoff.

- [ ] **Step 4: Run tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests '*SiteExplorerTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/appgate/tv/sitebrain/SiteExplorer.kt app/src/test/java/com/appgate/tv/sitebrain/SiteExplorerTest.kt
git commit -m "feat: add bounded breadth-first website explorer"
```

### Task 7: Central Search Brain and Multi-Path Planning

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/CentralSearchBrain.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/CentralSearchBrainTest.kt`
- Modify: `app/src/main/java/com/appgate/tv/SearchIntent.kt`

**Interfaces:**
- Produces: `SearchWave`, `SearchPath`, `CentralSearchBrain.plan(parsed, siteBrains): List<SearchWave>`.

- [ ] **Step 1: Write failing multi-path tests**

Create a fake Site Brain with two verified routes to rental inventory and assert both are selected for a timeshare/rental intent. Assert Wave 1 uses high-confidence known paths, Wave 2 adds alternate/related paths, and Wave 3 requests deep-detail verification for requirements absent from result cards.

- [ ] **Step 2: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*CentralSearchBrainTest*'`
Expected: FAIL.

- [ ] **Step 3: Extend intent representation**

Preserve current price/mileage/deep-term parsing but add generic concept terms, hard constraints, optional terms, and discovered vocabulary hooks without breaking existing tests.

- [ ] **Step 4: Implement search-wave planning**

Plan multiple known paths per relevant site, not just one URL. Track estimated path coverage and unresolved branches.

- [ ] **Step 5: Run existing and new intent/search tests**

Run: `./gradlew testDebugUnitTest --tests '*SearchIntentTest*' --tests '*CentralSearchBrainTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/appgate/tv/sitebrain/CentralSearchBrain.kt app/src/test/java/com/appgate/tv/sitebrain/CentralSearchBrainTest.kt app/src/main/java/com/appgate/tv/SearchIntent.kt
git commit -m "feat: plan broad multi-path searches from Site Brain knowledge"
```

### Task 8: Integrate Site Brain into BrowserActivity

**Files:**
- Modify: `app/src/main/java/com/appgate/tv/BrowserActivity.kt`
- Create: `app/src/main/java/com/appgate/tv/sitebrain/WebViewSiteBrainController.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/WebViewSiteBrainControllerTest.kt`

**Interfaces:**
- Consumes all previous Site Brain components.
- Produces controller states: `SEARCHING`, `EXPLORING`, `WAITING_FOR_HUMAN`, `VERIFYING`, `COMPLETE`.

- [ ] **Step 1: Write controller state tests**

Test normal snapshot -> safe action -> postcondition verification, login/challenge -> pause, resume -> re-snapshot, and budget exhaustion -> complete without looping.

- [ ] **Step 2: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*WebViewSiteBrainControllerTest*'`
Expected: FAIL.

- [ ] **Step 3: Move page-understanding logic out of BrowserActivity**

`BrowserActivity` becomes UI/WebView host. `WebViewSiteBrainController` owns snapshot capture, exploration decisions, verification, graph updates, learned-path reuse, and handoff state.

- [ ] **Step 4: Preserve challenge behavior**

When challenge/login signals appear, pause only that site flow and surface the existing Resume action. Do not clear the WebView session when remember-sign-ins is enabled.

- [ ] **Step 5: Keep generic extraction as fallback only**

Generic anchor extraction may produce `Possible Match` candidates while Site Brain confidence is low. It may not produce `Verified Match` without structured evidence.

- [ ] **Step 6: Run controller and regression tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/appgate/tv/BrowserActivity.kt app/src/main/java/com/appgate/tv/sitebrain/WebViewSiteBrainController.kt app/src/test/java/com/appgate/tv/sitebrain/WebViewSiteBrainControllerTest.kt
git commit -m "feat: integrate Site Brain learning into WebView search flow"
```

### Task 9: KSL Seed Knowledge and Live Learning

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/KslSeedKnowledge.kt`
- Create: `app/src/test/java/com/appgate/tv/sitebrain/KslSeedKnowledgeTest.kt`

**Interfaces:**
- Produces: `KslSeedKnowledge.seed(): SiteBrainState`.

- [ ] **Step 1: Write failing seed tests**

Assert the seed includes semantic concepts for vehicle make/model, maximum price, maximum mileage, result list, listing detail, and direct search-route hints without hard-coding DOM positions.

- [ ] **Step 2: Run tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*KslSeedKnowledgeTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement KSL seed graph**

Seed only knowledge we already verified: search entry points and semantic intent hints. Let live exploration discover/verify current controls and update confidence.

- [ ] **Step 4: Run tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests '*KslSeedKnowledgeTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/appgate/tv/sitebrain/KslSeedKnowledge.kt app/src/test/java/com/appgate/tv/sitebrain/KslSeedKnowledgeTest.kt
git commit -m "feat: seed KSL Site Brain knowledge"
```

### Task 10: Site Brain Status UI

**Files:**
- Modify: `app/src/main/java/com/appgate/tv/BrowserActivity.kt`
- Modify: `app/src/main/java/com/appgate/tv/MainActivity.kt`

**Interfaces:**
- Displays per-site readiness, learning/search state, paths checked, detail pages read, possible vs verified matches, and coverage estimate.

- [ ] **Step 1: Add plain-English status strings**

Examples: `Learning KSL Cars: 12 safe paths mapped`, `Searching alternate category 2 of 4`, `Waiting for sign-in`, `Deep checking listing description`, `KSL Cars: Search Ready`.

- [ ] **Step 2: Add a compact Site Brain summary panel**

Do not overwhelm the user with developer terminology. Show readiness, progress, and coverage evidence.

- [ ] **Step 3: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/appgate/tv/BrowserActivity.kt app/src/main/java/com/appgate/tv/MainActivity.kt
git commit -m "feat: show Site Brain learning and coverage status"
```

### Task 11: Version Bump, Full Verification, and APK Build

**Files:**
- Modify: `app/build.gradle`

**Interfaces:**
- Produces Android APK version `5.0.0-site-brain-alpha` with incremented `versionCode`.

- [ ] **Step 1: Bump version**

Set `versionCode` to `10` and `versionName` to `5.0.0-site-brain-alpha`.

- [ ] **Step 2: Run full unit suite**

Run: `./gradlew testDebugUnitTest`
Expected: all tests PASS.

- [ ] **Step 3: Build debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 4: Commit version bump**

```bash
git add app/build.gradle
git commit -m "build: release AI Browser v5 Site Brain alpha"
```

- [ ] **Step 5: Push branch and let GitHub Actions verify**

Expected workflow outcome: unit tests PASS, debug APK build PASS, APK artifact uploaded.

## Plan Self-Review

- Spec coverage: perception, semantic actions, graph persistence, Explorer, verifier, repair, live learning, Central Search Brain, challenge handoff, coverage/readiness, KSL seed, and APK build are all represented.
- Safety boundary is enforced before Explorer integration.
- Generic extraction remains fallback-only and cannot produce false verified results.
- v5 deliberately excludes server/community synchronization and visual-model integration; interfaces leave room for those later.
- No task requires Windows or a desktop workflow from the user.
