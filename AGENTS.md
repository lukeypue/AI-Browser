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
- `app/src/main/java/com/appgate/tv/sitebrain/`
- `app/src/main/java/com/appgate/tv/OvernightLearningActivity.kt`
- `sitebrain-core/`
- `sitebrain-lab/`

Prefer deterministic known-site execution. Use AI only for unknown/repair cases.

## Current maintainer priority
Read `docs/BRAIN_DEVELOPMENT_PRIORITY.md` before planning work. Prioritize retained server brain learning and measured improvement; defer product consent/distribution work while preserving existing boundaries.
