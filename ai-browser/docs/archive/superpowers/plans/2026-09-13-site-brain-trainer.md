# Site Brain Trainer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone Android Site Brain Trainer that safely and resumably learns the starter websites, merges public scout knowledge, pauses for legitimate human authentication, and reports trustworthy 95%+ verified searchable-interface coverage when earned.

**Architecture:** Reuse the existing `com.appgate.tv.sitebrain` semantic engine as the single source of truth. Add a trainer-specific orchestration/dashboard layer around WebView, persistent per-site checkpoints/frontiers, and a shared knowledge-pack importer. Keep the existing scheduled public scout as complementary evidence; authenticated/mobile evidence remains authoritative and private session material never leaves the phone.

**Tech Stack:** Android/Kotlin, Android WebView, SharedPreferences/JSON, JUnit 4, existing Site Brain Lab Kotlin/JVM module, Playwright public scout in GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-13-site-brain-trainer-design.md`

## Global Constraints

- Android is the authoritative authenticated training environment.
- Starter sites: KSL Cars, Facebook Marketplace, eBay, Craigslist, AutoTrader, Cars.com, CarGurus, Edmunds, TrueCar, CarMax, and OfferUp where normal user-facing interfaces permit access.
- User performs legitimate login, MFA, CAPTCHA, and human verification; automation never solves or bypasses them.
- Autonomous actions are read-oriented and reversible only: search, navigation, categories, filters, sort, pagination/infinite scroll, listing details, read-only expansion, and back navigation.
- Never autonomously buy, bid, order, checkout, pay, message/contact, post/publish/upload, delete/remove, follow/subscribe when account-changing, alter account/profile/security settings, accept agreements, or submit sensitive forms.
- Passwords, cookies, session tokens, authorization headers, MFA material, CAPTCHA answers, payment data, private messages, and private account content never enter exported packs/reports.
- 95% means verified coverage of discovered safe searchable structure, not listings or raw URLs. Protected boundaries are reported separately and never used to inflate coverage.
- Training checkpoints after meaningful transitions and resumes after interruption.
- Public GitHub scout is scheduled/repeated training, not an always-on server.

---

### Task 1: Trainer Site Catalog and Training State

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/trainer/TrainerModels.kt`
- Create: `app/src/main/java/com/appgate/tv/sitebrain/trainer/TrainerSiteCatalog.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/trainer/TrainerSiteCatalogTest.kt`

**Interfaces:**
- Produces: `TrainerSite(id, displayName, entryUrl, hostAliases)`, `TrainingStatus`, `TrainerSiteState`, and `TrainerSiteCatalog.starterSites`.

- [ ] **Step 1: Write the failing catalog test** asserting exactly the eleven approved starter sites exist, IDs are unique, URLs are HTTPS, and host aliases are nonempty.
- [ ] **Step 2: Run** `gradle testDebugUnitTest --tests '*TrainerSiteCatalogTest*' --stacktrace` and verify failure because trainer types do not exist.
- [ ] **Step 3: Implement minimal models/catalog** with statuses `NOT_STARTED`, `TRAINING`, `HUMAN_ATTENTION`, `PAUSED`, `TARGET_REACHED`, and `ERROR`.
- [ ] **Step 4: Re-run the focused test** and require PASS.
- [ ] **Step 5: Commit** `feat: add Site Brain Trainer site catalog`.

### Task 2: Persistent Checkpoint and Resume

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/trainer/TrainingCheckpointRepository.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/trainer/TrainingCheckpointRepositoryTest.kt`

**Interfaces:**
- Consumes: `TrainerSiteState` from Task 1.
- Produces: `load(siteId): TrainerSiteState?`, `save(state)`, `clear(siteId)`, `loadAll(): List<TrainerSiteState>`.

- [ ] **Step 1: Write failing tests** for save/load round-trip, per-site isolation, frontier preservation, last URL preservation, and no secret/session fields in serialized state.
- [ ] **Step 2: Run focused repository tests** and verify RED.
- [ ] **Step 3: Implement SharedPreferences-backed JSON checkpoint storage** containing structural frontier/action IDs, coverage/status, timestamps, and sanitized last route only.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `feat: persist resumable trainer checkpoints`.

### Task 3: Trustworthy Trainer Coverage

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/trainer/TrainerCoverage.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/trainer/TrainerCoverageTest.kt`

**Interfaces:**
- Produces: `TrainerCoverage.evaluate(brain: SiteBrainState): TrainerCoverageReport` with `percent`, `criticalMissing`, `protectedBoundaries`, and `targetReached`.

- [ ] **Step 1: Write failing tests** proving no brain reaches target without SEARCH, RESULTS, DETAIL, return navigation, and discovered category/filter/sort/pagination capabilities when present; protected boundaries do not raise the percentage; newly discovered unverified structure can lower coverage.
- [ ] **Step 2: Run focused coverage tests** and verify RED.
- [ ] **Step 3: Implement weighted structural coverage** using verified semantic edges and discovered safe capabilities, with `targetReached = percent >= 95 && criticalMissing.isEmpty()`.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `feat: add verified trainer coverage scoring`.

### Task 4: Human-Attention Boundary Detection

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/trainer/HumanAttentionPolicy.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/trainer/HumanAttentionPolicyTest.kt`
- Modify: `app/src/main/java/com/appgate/tv/sitebrain/WebViewSiteBrainController.kt`

**Interfaces:**
- Produces: `HumanAttentionPolicy.classify(snapshot): HumanAttentionReason?` for `LOGIN_REQUIRED`, `MFA_REQUIRED`, `HUMAN_VERIFICATION_REQUIRED`, `ACCESS_BLOCKED`.

- [ ] **Step 1: Write failing tests** using representative login/security/challenge snapshots and ordinary marketplace pages.
- [ ] **Step 2: Run focused policy tests** and verify RED.
- [ ] **Step 3: Implement semantic boundary policy** and expose controller observation without executing an action when a protected boundary is present.
- [ ] **Step 4: Re-run policy plus existing Site Brain controller tests** and require PASS.
- [ ] **Step 5: Commit** `feat: pause trainer for legitimate human verification`.

### Task 5: Autonomous Training Orchestrator

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/trainer/TrainerOrchestrator.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/trainer/TrainerOrchestratorTest.kt`
- Reuse: `AutonomousLearningPolicy.kt`, `SiteExplorer.kt`, `OutcomeVerifier.kt`, `ActionRepairer.kt`, `SafeActionExecutor.kt`.

**Interfaces:**
- Consumes: site catalog, checkpoint repository, `WebViewSiteBrainController`, `TrainerCoverage`.
- Produces: `startAll()`, `startSite(siteId)`, `pause()`, `resumeAfterHuman()`, `onPageObserved(snapshot)`, `nextCommand(): TrainerCommand`.

- [ ] **Step 1: Write failing orchestration tests** for weakest-site-first selection, repeated safe actions until target/budget, checkpoint after meaningful transition, stagnation rerouting, protected-boundary pause, and target-reached rotation to next site.
- [ ] **Step 2: Run focused orchestrator tests** and verify RED.
- [ ] **Step 3: Implement deterministic state machine**; never emit an execution command when safety classifier or human-attention policy blocks the action/page.
- [ ] **Step 4: Re-run focused tests and existing explorer/safety tests** and require PASS.
- [ ] **Step 5: Commit** `feat: orchestrate autonomous Site Brain training`.

### Task 6: Keep Website Navigation Inside Trainer WebView

**Files:**
- Modify: `app/src/main/java/com/appgate/tv/BrowserActivity.kt` or extract shared client to `app/src/main/java/com/appgate/tv/sitebrain/SiteBrainWebViewClient.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/WebNavigationPolicyTest.kt`

**Interfaces:**
- Consumes: existing `WebNavigationPolicy` decisions `KEEP_IN_WEBVIEW`, `LOAD_WEB_FALLBACK`, `BLOCK_EXTERNAL_APP`.

- [ ] **Step 1: Extend failing navigation tests** for eBay HTTPS staying in WebView, `intent://` safe browser fallback, custom app scheme block, and no external-app launch during training.
- [ ] **Step 2: Run focused navigation tests** and verify RED for the integration behavior.
- [ ] **Step 3: Wire the policy into the WebViewClient** shared by Trainer; load only validated HTTP(S) fallback URLs.
- [ ] **Step 4: Re-run navigation tests** and require PASS.
- [ ] **Step 5: Commit** `fix: keep trainer navigation inside the web`.

### Task 7: Site Knowledge Pack Import and Evidence Merge

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/KnowledgePackImporter.kt`
- Modify: `app/src/main/java/com/appgate/tv/sitebrain/SiteBrainRepository.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/KnowledgePackImporterTest.kt`

**Interfaces:**
- Consumes: Site Brain Lab pack schema/version and local `SiteBrainState`.
- Produces: `parse(bytes): ImportedKnowledgePack`, `merge(pack, local): SiteBrainState`.

- [ ] **Step 1: Write failing tests** for valid pack import, incompatible schema rejection, alternate-path retention, stronger authenticated/mobile evidence winning over newer weaker public evidence, and duplicate semantic edge merge.
- [ ] **Step 2: Run focused importer tests** and verify RED.
- [ ] **Step 3: Implement parser/validator/merge** against the actual current Lab pack format; do not infer or import session/private values.
- [ ] **Step 4: Re-run focused tests plus Lab `KnowledgePackTest`** and require PASS.
- [ ] **Step 5: Commit** `feat: merge public Site Brain knowledge on Android`.

### Task 8: Privacy-Safe Learning Report

**Files:**
- Create: `app/src/main/java/com/appgate/tv/sitebrain/trainer/TrainingReportExporter.kt`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/trainer/TrainingReportExporterTest.kt`

**Interfaces:**
- Produces: `buildReport(states, brains): String` containing structural learning only.

- [ ] **Step 1: Write failing tests** containing fake emails, phone numbers, cookies, session/token/password strings, auth query parameters, private message text, and safe semantic facts; assert sensitive material is absent and structural facts remain.
- [ ] **Step 2: Run focused exporter tests** and verify RED.
- [ ] **Step 3: Implement sanitizer/exporter** using route templates, page types, semantic actions, confidence, outcome classes, coverage, timestamps, and boundary reasons only.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `feat: export privacy-safe trainer learning reports`.

### Task 9: Standalone Trainer Dashboard

**Files:**
- Create: `app/src/main/java/com/appgate/tv/SiteBrainTrainerActivity.kt`
- Create/Modify: Android layout/resources or programmatic view resources used by the project.
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/appgate/tv/sitebrain/trainer/TrainerDashboardModelTest.kt`

**Interfaces:**
- Consumes: orchestrator states and coverage reports.
- Produces: dashboard with `TRAIN ALL SITES`, per-site Train button, status/coverage/activity/last-trained/human-attention display, Pause/Resume, and Share Learning Data.

- [ ] **Step 1: Write failing dashboard-model tests** proving coverage labels use verified coverage, human-attention sites are visibly distinct, target-reached sites show 95%+, and Train All prioritizes unfinished sites.
- [ ] **Step 2: Run focused dashboard tests** and verify RED.
- [ ] **Step 3: Implement standalone Activity/dashboard** with a WebView training surface and simple non-technical copy; keep login/security interaction visible to the user when required.
- [ ] **Step 4: Re-run dashboard tests and Android unit suite** and require PASS.
- [ ] **Step 5: Commit** `feat: add standalone Site Brain Trainer dashboard`.

### Task 10: Improve Results/Detail Recognition and Repeated Verification

**Files:**
- Modify: `app/src/main/java/com/appgate/tv/sitebrain/SemanticPageSnapshot.kt`
- Modify: `sitebrain-lab/live-scout/scout-policy.mjs`
- Modify: `sitebrain-lab/live-scout/public-scout.mjs`
- Test: corresponding Android semantic snapshot tests and `sitebrain-lab/live-scout/scout-policy.test.mjs`.

**Interfaces:**
- Produces: consistent `RESULTS`/`DETAIL` recognition for marketplace URL families and repeated evidence promotion.

- [ ] **Step 1: Add failing Android and scout tests** for Craigslist item IDs, CarGurus inventory/detail routes, CarMax car detail routes, OfferUp item routes, and false-positive research/legal/community routes.
- [ ] **Step 2: Run both focused suites** and verify RED.
- [ ] **Step 3: Implement route/page semantic recognition and discovery scoring** that prefers search/result/detail structure and deprioritizes unrelated community/legal/research areas.
- [ ] **Step 4: Re-run both suites** and require PASS.
- [ ] **Step 5: Commit** `feat: deepen marketplace structural learning`.

### Task 11: Repeated Public Scout Schedule and Honest Artifacts

**Files:**
- Modify: `.github/workflows/sitebrain-scout.yml`
- Test: scout policy tests plus workflow run evidence.

**Interfaces:**
- Produces: scheduled public scout artifacts containing per-site pack and `summary.json`; protected sites remain honestly blocked rather than falsely marked learned.

- [ ] **Step 1: Configure a conservative recurring schedule** no more frequent than hourly and retain manual dispatch/push testing.
- [ ] **Step 2: Run scout policy tests** before live crawl.
- [ ] **Step 3: Run a live scout workflow** and inspect artifact summary for coverage, boundaries, capabilities, and no false 100%.
- [ ] **Step 4: If live observations expose a deterministic policy bug, add a failing fixture/policy test before fixing it; otherwise preserve the observation as evidence, not a fixture.**
- [ ] **Step 5: Commit** `ci: repeat public Site Brain training`.

### Task 12: Release Verification and Trainer APK

**Files:**
- Modify: `app/build.gradle` version metadata.
- Modify: user-facing title/version resources as applicable.

**Interfaces:**
- Produces: installable standalone Trainer debug APK for real phone training.

- [ ] **Step 1: Run Lab tests:** `gradle :sitebrain-lab:test --stacktrace`; require PASS.
- [ ] **Step 2: Run Android tests:** `gradle testDebugUnitTest --stacktrace`; require PASS.
- [ ] **Step 3: Run scout policy tests:** `node --test sitebrain-lab/live-scout/scout-policy.test.mjs`; require PASS.
- [ ] **Step 4: Build:** `gradle assembleDebug --stacktrace`; require PASS and uploaded APK artifact from exact head commit.
- [ ] **Step 5: Verify exact-head workflow statuses and artifact digest before claiming completion.**
- [ ] **Step 6: Commit version bump** `release: Site Brain Trainer alpha` and repeat exact-head CI after the version commit.

## Plan Self-Review

- Spec coverage: standalone Android trainer, hybrid public scout, authenticated human boundary, safety, privacy, checkpoints/resume, 95% verified structural coverage, pack merge, repair/stagnation, dashboard, repeated scout, and release verification are all assigned to tasks.
- Placeholder scan: no TBD/TODO/implement-later placeholders are present.
- Type consistency: trainer catalog/state feeds checkpoint/orchestrator/dashboard; `TrainerCoverageReport` is the sole dashboard coverage source; knowledge packs merge into existing `SiteBrainState`; existing `WebNavigationPolicy` remains the navigation authority.
- Scope: AI Browser consumption of mature Trainer packs is intentionally deferred until the standalone Trainer demonstrates trustworthy learning, matching the approved spec.
