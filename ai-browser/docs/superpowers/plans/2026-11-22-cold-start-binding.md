# Cold-start binding implementation plan
> **For agentic workers:** Use superpowers:executing-plans inline. No implementation delegation.
**Goal:** Localize and fix the failed cold startup and publish only a verified upgrade.
**Architecture:** Independent closed debug watchdog, existing protected emulator transaction, evidence-led focused correction.
**Tech Stack:** Kotlin/JUnit, Android instrumentation, Python server transaction.
**Spec:** ai-browser/docs/superpowers/specs/2026-11-22-cold-start-binding-design.md
## Constraints
No secrets/signing/workflow/phone changes. Existing City/uploader/review retained. Failed bedf trial immutable.
## Review focus
Unknown stack names must be dropped. Exact UUID/revision binding. No main-thread diagnostic IO. Watchdog close and thread-state races. Failed experiment must restore all metadata/APKs/memory before resumption.
## Task1 diagnostic localization
- [ ] Write failing closed sampler tests: known/unknown/both threads, stage, bound, write failure, close.
- [ ] Implement smallest independent debug watchdog and instrumentation hook; run app tests/build.
- [ ] Independently review exact source/transaction and run one protected experiment, preserve all outcomes.
## Task2 supported correction
- [ ] Reproduce observed cause, minimal fix, full verification and fresh review.
- [ ] Cold qualification/old restoration/actual evidence approval.
- [ ] Existing expected-head signed release, all CI and independent artifact checks; reviewed rollout and actual City reports.
