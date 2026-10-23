# Pagination Scroll Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline; one fresh whole-branch reviewer at the end. Steps use checkbox syntax.

**Goal:** Safely generalize an observed scroll prerequisite into bounded conditional pagination replay.

**Architecture:** Typed optional Step guard; compiler receives observed transition proof. Engine skips/repeats only that guard and preserves verifier-only reward.

**Tech Stack:** Existing pure Kotlin/JUnit/JDK17 and GitHub Android validation.

**Spec:** docs/superpowers/specs/2026-10-23-pagination-scroll-design.md

## Global Constraints
Preserve old fixtures/oracles/manifests, human boundaries, explicit budgets, privacy, signing, workflows and existing publication ownership. No phone-gain claim.

## Review Focus
Unknown geometry must fail closed. Guard spoofing must not bypass verifier. Target identity drift must stop. Scroll-induced new results must never be followed by a click. Legacy JSON must retain literal semantics.

### Task 1: Verified conditional prerequisite
- [x] Add independent renderer tests with 500px demonstration and 0/500/1000/2500px evaluation, JSON reload and true skill removal; freeze fixture source before implementation.
- [x] Run RED against unchanged core, expecting wasted-scroll/distance failure.
- [x] Add optional Step until_role, geometry helper, observed compiler proof and bounded engine replay. Unknown geometry/identity/no-op/human/budget/autoload controls remain safe.
- [x] Run focused tests GREEN, original district tests and full core checks. Run browser/receiver/runner checks without modifying their safety expectations.
- [x] Obtain Claude critique and one independent whole-branch source review; address material findings with RED/GREEN.
- [ ] Publish isolated branch with expected-head normal FF and run full Android CI. Do not race existing stable publisher/server owner.

## Execution record
- Baseline original district4/4 passed. Frozen geometry fixture commit e739090 precedes runtime implementation.
- Initial8 tests:6 expected failures. A declared-guard proof regression was then observed RED. First9 focused tests and497 full core tests passed before independent review.
- Independent reviewer adaptive_scroll_review found4 Important issues: ordinal identity, delayed autoload, guarded retry bypass, and end-of-scroll quarantine. All4 were reproduced RED and corrected in one fix pass.13 focused tests pass after fixes.
- Actual Chromium RED run39994234819/sourcee88565a: original23 perception checks passed; both new ordinal/replacement tests failed for their intended assertions (a1 becamea0; a replacement retaineda0).
- Ruling: extend the correction to existing extractor address generation and extension bridge version, preserving the wire field and all original fixtures. Cost if wrong: an ordinal-assuming consumer could regress; original browser, core and Android checks remain gates.
- Local full scripts110 and receiver100 suites cannot fully pass in this workspace:31 and15 errors respectively require the absent aibrowserupload system group. Normal group setup is denied by this runtime's audit interface. Do not alter or skip their safety checks; the existing publication owner's full guarded checks remain required.
- Ruling: preserve the original macro district's fixtures and known failed counterfactual; add separate honest geometry tests. Cost if wrong: results could be overinterpreted; no promotion/phone/general-learning claim is made from this isolated fixture.
- Final verification in progress: all13 focused engine tests and4 document-boundary checks pass. The501-test full core run has2 provenance failures because the intentionally pinned DialogClinic recordings still name the old extractor. Recapture the unchanged12 cases in actual Chromium; never relax the hash check or hand-edit observations. The original case manifest and physical oracle must remain unchanged.
- Claude participated through the signed-in conversation b0e4aa9c-0daa-46c0-878a-aa4de8ec4617, offering design/fix-pass critique. This is advice, not a rerun or an independent code approval. The observed pre-click context checks cannot prove causality across every asynchronous real-site race.
- Actual Chromium recapture succeeded in isolated unsigned build39997580825, source d40f768a2051d6ba63960ee79b1a9a4164c02769. All26 browser checks passed (25 regressions/original checks plus capture), Android unit tests and debug assembly passed. Captured extractor70d949c5f3b8293d6ae10659e50d74bb0cdb62b2ba6fc8fc2f99b1f57090b6b2; exact recordingacf673b66f54cb09ee5891d070c17c6663caf87a68c7e50c48c9bb3e4089822b. All12 case IDs, splits and expected outcomes retain manifest94d28e1439971863a0867207d29fef3c986b32ffde22937e861548b720158981. Original bytes remain archived as dialog-clinic-v1.json with original SHAe0e30c1f063c8697c1d569a7620f3f1869aa1c27e3bdd357e9cb198b2418ecfa. No oracle/provenance check was weakened.
- The earlier capture run39995854732 was superseded while blocked downloading Ubuntu fonts; its browser tests had not started. This was dependency delivery latency, not an established capture or engine failure. Temporary capture-helper changes are excluded from the integration tree.
- Final local core verification after the real recapture:501/501 passed in160.393s, including13 pagination-prerequisite regressions and the unchanged district/clinic tests. All12 initial physical states and transition inside/ack/closed outcomes match the original recording. Document-boundary4/4 and bounded runner16/16 passed. Full host scripts/receiver checks remain environment-blocked as recorded above; this is not an all-checks-passed or publication-ready assertion.
- Stable head remainsdd1efe2ac6644b347dd77001063450527b20a9fe. Final integration-tree Android/browser CI is a separate gate from the successful capture staging build. Publish only through the existing trusted-host guard with its lock, full checks, exact-candidate review attestation and verified signed artifact. This thread has no supported connection to that host and makes no server promotion, signed release, phone install or live improvement claim.
