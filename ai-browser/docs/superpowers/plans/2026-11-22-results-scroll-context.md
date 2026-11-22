# Results Scroll Context Implementation Plan
> **For agentic workers:** Use superpowers:executing-plans inline. Follow RED/GREEN and independent review.
**Goal:** A scroll action reaches the observed primary results panel, yields verifiable new results and retains learning without repeating an obsolete root-scroll failure.
**Architecture:** Reuse canonical observed listing links in content.js to choose the innermost common scrollable ancestor. Export separate bounded typed scroll geometry; parse it in SpsParser/SemanticPageState, compare matching geometry in Verifier and scope only SCROLLfailure identities to the new observed context. No selector/URL enters learned memory.
**Tech Stack:** Existing Kotlin/JUnit, JS/Chromium, GeckoView, Python signed-release controller.
**Spec:** ../specs/2026-11-22-results-scroll-context.md
## Global constraints
Privacy/signing/workflows/credentials/human auth boundaries, timers/uploader/review and global budgets remain unchanged. Built-in extension version must advance for changed content.js; Android version remains automation-owned.
## Review focus
- A larger unrelated sidebar never receives primary-results movement.
- Root and inner offsets cannot fabricate movement or bottom verification.
- Stale/missing/hidden/disabled/changed listing links do not choose an unrelated surface.
- Old ROOTfailure remains saved/held; newcontext identity is bounded and stable across ordinary listing churn/restart.
- No-op, busy, end-of-list and human states never grant false newlearning.
## Tasks
- [ ] Browser RED: current reader scrolls root while panel items stay unchanged. Add root/sidebar/no-op/bottom controls.
- [ ] Kotlin RED: parse bounded optional scroll context; reject malformed geometry; verify matching context and preserve legacy root keys/holds.
- [ ] Minimal content.js/SpsParser/Sps/Verifier/FailedStrategies change; bump extension manifest only; run focused/browser GREEN.
- [ ] Whole-engine Chromium new-result/reload/changedlayout acquisition and negative controls; optional actual AI candidate only if needed and supported.
- [ ] Fullsource/Android/City checks, exactsource independent review; commit clean candidate.
- [ ] Existing signed pipeline only after all gates; all3CI/artifact pins; reviewed source-bound City/native rollout; verify report retrieval; handoff existingreview. No phoneinstall.
