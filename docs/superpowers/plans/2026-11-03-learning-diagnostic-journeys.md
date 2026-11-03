# Learning diagnostic journeys implementation plan

> Execute inline with executing-plans. Use test-driven development and one independent whole-branch review. Existing maintainer approvals cover implementation and the stable signed pipeline.

**Goal:** Explain stalled learning from sanitized evidence, distinguish verified movement from new learning, and make City failures replayable without changing rewards.
**Architecture:** Shared strict event projection for phone/City. Process-local bounded task/program/action counters connect decisions, outcomes and memory. Optional schema2 extensions retain backward compatibility. App whole-test totals come from the test owner before event trimming. City keeps bounded traces plus exact streaming aggregates and publishes independently validated samples through the existing writer.
**Spec:** Owner request to improve both City and app diagnostics using the existing60minute report; docs/BRAIN_DEVELOPMENT_PRIORITY.md and ai-browser/docs/BRAIN_ARCHITECTURE.md.
**Stack:** pure Kotlin core/lab, existing Android service, Python independent receiver and synthetic publisher.

## Constraints
No personal text, URL, query, credentials, hashes of site content, raw prompts or arbitrary extras. Keep report1MiB, queue10reports/10MiB,7day retries, consent and human boundaries. Preserve timers/uploader/review automation, signing and frozen City scores. Do not claim actual phone gains from synthetic traces.

## Review focus
Legacy schema1/2 interoperability; unknown enums/counter injection; trim-safe whole-test totals and late/cancelled test events; bounded sample selection and independent City validation; truthful planner invocation versus actual provider transport.

## Task1: App/core diagnostic evidence
- [ ] RED: exporter must preserve only typed journey coordinates and summary totals, reject/drop injected values; real engine must correlate two tasks and emit planner result/cooldown schedule evidence.
- [ ] Implement a shared projection helper, transient ledger counters, typed planner outcome events, and post-schedule counters. Add whole-test counters owned by TimedLearningTest and bound by the existing test owner in BrainService. No behavior/reward changes.
- [ ] GREEN: focused and full core/app suites. Independently validate optional schema2 additions in receiver; legacy schemas remain strict.

## Task2: City traces and publication
- [ ] RED: real SimulationTrainer episodes expose bounded sanitized traces; publisher exports validated stage totals and samples and rejects malformed, conflicting or private evidence.
- [ ] Add a separate, unscored four-case description-prerequisite diagnostic clinic (detail, unknown after click, no-op click, human boundary), with independently validated expected diagnostics. Capture engine events with bounded first/last trace and exact aggregates. Add source/case replay context through existing manifests, stage summaries and8worst-case samples. Keep frozen exam metrics unchanged.
- [ ] GREEN: full core/app, runner/receiver/publisher, browser harnesses, Android build/lint and City preflight.
- [ ] Actual Claude source/evidence advice and focused critique; independent review; address blocking findings test-first.
- [ ] Deploy independent validators/publisher before app via tested guarded installation preserving state and current uploads. Publish under existing locked expected-head signed pipeline; source-sync City under existing locks and verify a new synthetic diagnostic report can be retrieved. No phone installation; actual new phone diagnostics require installed build and test.
