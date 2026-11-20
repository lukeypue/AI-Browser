# Persistent Planner Authentication Hold Implementation Plan

> For agentic workers: use superpowers:executing-plans; owner authorizes native execution without repeated approval.

**Goal:** Stop repeated rejected planner requests across tasks/restarts while keeping local skills running.
**Architecture:** Pure Kotlin durable gate wraps PlannerClient before delegate budget/transport; shared Android adapter owns app-private state and existing settings SAVE resets generation.
**Tech Stack:** Existing Kotlin/JVM, Android, FileBrainStorage, JUnit; no dependencies.
**Spec:** docs/superpowers/specs/2026-11-20-planner-authentication-hold-design.md

## Global Constraints
No credentials inspected/changed; no schema/scoring/signing/workflow/version changes; preserve existing server workers and single review; Claude skipped; no physical phone installation.

## Review Focus
Old401 after settings SAVE must not block the new generation. Corrupt/unreadable state fails closed. Persistence failure must stop same-process repeats. Master/unrestricted/test restarts cannot reset the hold. No request budget is reserved for held or stale clients.

### Task1: Core gate and app integration
- [ ] RED: first401 persists; reload/repeatedclients do not call fake transport or reserve allowance; explicit save retries once; stale401 ignored; non401 unchanged; corrupt/read/write failures and file reload tested.
- [ ] Implement PlannerAuthenticationHold and AuthenticationGuardedClient in brain-core/planner; dedicated Android PlannerConnectionStore; app factory checks hold before key/budget; settings SAVE resets only after existing save succeeds; home/learning status messages.
- [ ] GREEN: full core/app tests, preserving existing skills/site memory and master-off behavior.

### Task2: City regression and release
- [ ] RED/GREEN: existing authentication control checks reloaded gate suppresses repeated delegate calls; retain fixed8cases and closed report schema.
- [ ] Run runner/receiver/publisher/browser/Android fixtures, assemble/lint and real City preflight. Independent exact-source review; fix findings with regression tests.
- [ ] Commit clean candidate; verify pending older server promotion before publishing; unchanged serialized stable release controller, all3exact-source CI, stable certificate/APK/source/hash verification. Deploy City safely and verify uploaded immutable report through connector. Report precise unresolved provider-authentication access and runtime states.
