# Site Brain v3 — B1 Isolation Design

Date: 2026-09-13
Branch: `ai-browser-v6-deep-search`
Scope: B1 only. No WebMCP, URL grammar induction, Interaction Graph pathfinding, autonomous Map-Site curriculum, shared packs, or ML repair in this phase.

## Goal

Convert the existing Site Brain from Android/WebView-coupled logic into a standalone, testable core that can later run behind Android WebView, desktop Playwright, CI fixture replay, or another host without changing the brain itself.

The core rule is: **the brain learns the WEBSITE, not the current SEARCH.** B1 does not yet expand mapping behavior; it creates the boundary that makes later B1.5–B5 work possible.

## Architectural invariants

1. `sitebrain-core` is a pure Kotlin/JVM module and must not import Android, `WebView`, `Context`, `SharedPreferences`, or UI classes.
2. The brain communicates with a browser only through a `BrowserPort` interface.
3. Browser observations are represented as platform-neutral `Observation` data.
4. Browser actions are represented as platform-neutral `Action` data.
5. Human/security boundaries are explicit; credentials/cookies never enter Skill Packs.
6. Site knowledge can be exported/imported as a versioned Site Skill Pack JSON artifact.
7. Existing Android behavior remains operational during B1; this is an isolation milestone, not a search-feature rewrite.
8. CI exercises the brain with `FakeBrowserPort` without Android or network access.

## Module layout

### `:sitebrain-core`
Pure Kotlin/JVM module containing:
- `BrowserPort`
- `Observation`
- `InteractiveElement`
- `Action`
- `ActResult` / `NavResult`
- `Boundary`
- `BrainHost`
- Skill Pack data models
- deterministic Skill Pack JSON codec
- pack store interface

### `:app`
Keeps Android UI and WebView ownership. Adds a WebView adapter that translates WebView page state into core `Observation` values and core `Action` values back into WebView operations. Android continues to own cookies and authentication.

### `:sitebrain-lab`
Depends on `:sitebrain-core` and hosts offline replay tests. `FakeBrowserPort` is test-only and proves the core can run without Android.

## BrowserPort contract

```kotlin
interface BrowserPort {
    fun navigate(url: String): NavResult
    fun observe(): Observation
    fun act(action: Action): ActResult
    fun screenshot(): ByteArray?
    fun isAuthenticated(site: String): Boolean
}
```

B1 uses synchronous methods because the current code is synchronous. Coroutines are deferred so this phase isolates responsibilities without forcing an unrelated async rewrite.

## Portable observation model

`Observation` carries only portable browser facts:
- `url`, `host`, `title`
- `pageType`, `routeSignature`
- compact interactive elements: reference, role, label, value, href, bounds
- headings / landmark labels
- `challengeDetected`, `loginDetected`
- structural hashes / optional metadata

No Android class may appear in these types.

## Portable action model

`Action` supports:
- `Navigate(url)`
- `Click(ref)`
- `Type(ref, text)`
- `Select(ref, value)`
- `Scroll(dx, dy)`
- `Back`
- `Wait(condition, maxMs)`

The core never executes JavaScript or touches a WebView directly. Hosts decide how each action is carried out.

## Human/security boundaries

`Boundary` captures login, CAPTCHA, 2FA, paywall, and protected-action states. `BrainHost.humanNeeded(boundary)` tells the host to surface the browser to the user. Cookies, credentials, and session tokens stay outside the core and are excluded from Skill Packs.

## Skill Pack v1

A B1 Skill Pack is intentionally minimal but versioned:
- schema version
- domain
- pack version
- creation / last verified timestamps
- page type records
- control records
- skill records
- route records
- evidence records
- metadata / provenance map

B1 acceptance requires deterministic export/import round-trip equality. Signing is deferred to B5.

## Android adapter

`WebViewBrowserPort` lives in `:app` and implements `BrowserPort`. B1 only needs enough adapter behavior to demonstrate the boundary and preserve existing app behavior. Existing mapping code may continue running during migration; new core code must not depend on it.

## Test strategy

### Core unit tests
- core source compiles with Kotlin/JVM only
- action/observation models construct and compare correctly
- Skill Pack codec round-trips deterministically

### Fake browser replay
A `FakeBrowserPort` in `sitebrain-lab` replays a small fixture graph from in-memory observations and expected actions. It must run under plain JUnit with no Android imports or network.

### Existing regression suite
All current Site Brain lab tests and Android unit tests must remain green, and the debug APK must still build.

## B1 acceptance tests

1. App builds and existing searches still work at compile/regression level.
2. A plain JUnit test loads/replays a fixture through `FakeBrowserPort` with zero Android imports.
3. Skill Pack export → import → export is byte-identical for the canonical fixture pack.
4. GitHub Actions runs the existing Site Brain lab tests, Android unit tests, and debug APK build successfully.

## Explicitly deferred

B1 does not implement:
- WebMCP discovery
- URL grammar induction
- full Interaction Graph pathfinding
- autonomous whole-site curriculum
- ELO locator scoring
- bounded repair ladder
- shared packs / signing
- visual grounding
- multi-user learning

Those depend on the isolation boundary and will be layered on only after B1 is green.