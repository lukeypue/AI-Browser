# AI Browser Engineering Agent

This repository contains the Android AI Browser / Site Brain project.

## Mission
Improve the Site Brain's ability to safely understand and navigate websites. Focus on reusable engine capabilities rather than one-off hacks for a single page.

## Non-negotiable rules
- Never bypass CAPTCHA, 2FA, login protections, paywalls, or access controls.
- Never add credential harvesting, hidden data collection, surveillance, or account automation.
- Never expose, print, read, modify, or commit signing keys, API keys, passwords, cookies, session tokens, or GitHub secrets.
- Do not modify `signing/`, release signing configuration, or GitHub workflow files unless the task explicitly comes from a trusted human maintainer.
- Do not weaken Android security settings or add new dangerous permissions.
- Keep personal browsing/search/session data local by default.
- Prefer generic site skills and capability fixes over domain-specific brittle selectors.
- Preserve human-only boundaries for CAPTCHA/login/2FA/payment/destructive/account-changing actions.

## Engineering loop
1. Read the capability-gap input and the relevant Site Brain code.
2. Reproduce the missing capability with a focused unit test or fixture.
3. Implement the smallest reusable fix.
4. Run the relevant tests.
5. Do not bump app version numbers; release automation owns versioning.
6. Summarize what changed, tests run, and remaining limitations.

## Main code areas
- `brain-core/` — the Site Brain itself (pure Kotlin, zero dependencies, fully unit-tested on the JVM).
  Read `docs/BRAIN_ARCHITECTURE.md` before changing it.
  - `perception/` semantic page state (roles, facets, page types, redaction)
  - `verify/` typed postconditions — the only source of reward
  - `skills/` site-agnostic skills, grounding, skill compiler
  - `engine/` planner/executor/verifier loop, task policy, curriculum, sessions
  - `memory/` site models, bindings, Beta stats, consolidation
  - `planner/` external model client (redacted structured requests only)
  - `profile/` site profiles as data (the replacement for per-site "mini brain" code)
- `app/src/main/assets/sitebrain/` — the bundled WebExtension (content script = perception + actions; background = network interlock)
- `app/src/main/java/com/appgate/tv/browser/` — GeckoView session, popups, prompts, off-screen display, renderer
- `app/src/main/java/com/appgate/tv/service/BrainService.kt` — foreground service owning the engine
- `app/src/main/java/com/appgate/tv/ui/` — screens

Rules of the architecture:
- Skills never contain selectors. Site knowledge is data (bindings, vocabulary, profiles), not code.
- The verifier is the only thing that may write success/failure into memory. "Clicked" is never an outcome.
- COMMIT_EXTERNAL actions (send/buy/bid/post/delete/follow/save/report) require a single-use user grant in the executor AND are blocked at the network layer in TRAIN mode.
- Auth walls and challenges are never sent to a model and never explored.
- Nothing personal is persisted: no listing text, names, queries, cookies or keys in memory, episodes or diagnostics.

Prefer deterministic execution through skills. Use the planner only for unknown/repair cases, and compile what it learns into skills.
