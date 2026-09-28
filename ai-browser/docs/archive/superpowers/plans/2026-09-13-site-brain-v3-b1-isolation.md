# Site Brain v3 B1 Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a pure Kotlin/JVM Site Brain core with a portable browser boundary, deterministic Skill Pack codec, and FakeBrowserPort replay while keeping the Android app building.

**Architecture:** Add `:sitebrain-core` as a pure JVM module. Android keeps WebView/session ownership through an adapter. `:sitebrain-lab` depends on the core and proves fixture replay without Android.

**Tech Stack:** Kotlin 1.9.24, Gradle, JUnit 4.13.2, Android WebView adapter in app module.

**Spec:** `docs/superpowers/specs/2026-09-13-site-brain-v3-b1-isolation-design.md`

## Global Constraints
- Core must contain zero Android/WebView imports.
- Existing app behavior must remain build-compatible.
- No WebMCP, URL grammar, graph pathfinding, autonomous mapper, signing, ML repair, or shared learning in B1.
- CI must run core/lab tests, Android tests, and APK build.

---

### Task 1: Add pure JVM core module and portable browser contract

**Files:**
- Modify: `settings.gradle`
- Create: `sitebrain-core/build.gradle`
- Create: `sitebrain-core/src/main/kotlin/com/appgate/sitebrain/core/BrowserPort.kt`
- Test: `sitebrain-core/src/test/kotlin/com/appgate/sitebrain/core/BrowserPortModelTest.kt`

**Interfaces:**
- Produces `BrowserPort`, `Observation`, `InteractiveElement`, `Rect`, `Action`, `NavResult`, `ActResult`, `Boundary`, `BrainHost`.

- [ ] Write model tests proving actions and observations are platform-neutral value objects.
- [ ] Run `./gradlew :sitebrain-core:test` and confirm RED because module/types do not exist yet.
- [ ] Add module, build file, and portable models.
- [ ] Run `./gradlew :sitebrain-core:test` and confirm GREEN.
- [ ] Commit.

### Task 2: Add deterministic Skill Pack model/codec

**Files:**
- Create: `sitebrain-core/src/main/kotlin/com/appgate/sitebrain/core/SkillPack.kt`
- Create: `sitebrain-core/src/test/kotlin/com/appgate/sitebrain/core/SkillPackCodecTest.kt`

**Interfaces:**
- Produces `SiteSkillPack`, `PageTypeRecord`, `ControlRecord`, `SkillRecord`, `RouteRecord`, `EvidenceRecord`, `SkillPackCodec.encode/decode`.

- [ ] Write failing canonical round-trip test asserting `encode(decode(encode(pack))) == encode(pack)`.
- [ ] Run core test and verify RED.
- [ ] Implement deterministic JSON escaping/ordering codec without Android dependencies.
- [ ] Run core tests and verify GREEN.
- [ ] Commit.

### Task 3: Add FakeBrowserPort replay in sitebrain-lab

**Files:**
- Modify: `sitebrain-lab/build.gradle`
- Create: `sitebrain-lab/src/test/kotlin/com/appgate/sitebrain/lab/FakeBrowserPort.kt`
- Create: `sitebrain-lab/src/test/kotlin/com/appgate/sitebrain/lab/FakeBrowserPortReplayTest.kt`

**Interfaces:**
- Consumes core `BrowserPort` and portable models.
- Produces an in-memory deterministic replay harness.

- [ ] Write failing replay test: navigate fixture URL, observe RESULTS state, click result ref, observe DETAIL state, back to RESULTS.
- [ ] Run `./gradlew :sitebrain-lab:test` and verify RED.
- [ ] Add `implementation project(':sitebrain-core')` and FakeBrowserPort implementation.
- [ ] Run lab tests and verify GREEN with no Android imports.
- [ ] Commit.

### Task 4: Add Android WebView adapter boundary

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/java/com/appgate/tv/sitebrain/WebViewBrowserPort.kt`
- Test: existing Android unit/build suite.

**Interfaces:**
- Consumes core `BrowserPort`.
- Produces `WebViewBrowserPort(webView, authenticatedHostProvider)` translating portable actions to WebView calls and compact observations from injected JavaScript.

- [ ] Add core project dependency to app.
- [ ] Implement adapter without moving current production exploration yet.
- [ ] Run `./gradlew testDebugUnitTest` and fix compile/test regressions.
- [ ] Commit.

### Task 5: CI integration and full verification

**Files:**
- Modify: `.github/workflows/build.yml`

**Interfaces:** none; release gate only.

- [ ] Add `./gradlew :sitebrain-core:test :sitebrain-lab:test` before Android unit tests.
- [ ] Run/trigger workflow on branch.
- [ ] Verify same workflow run passes Site Brain Lab, core tests, Android unit tests, debug APK build, and artifact upload.
- [ ] Download artifact and provide flattened APK to user.

## Self-review
- Spec coverage: all B1 acceptance criteria mapped to Tasks 1–5.
- No placeholders/TODOs.
- Types are defined before consumers.
- B1 scope deliberately excludes B1.5+ features.