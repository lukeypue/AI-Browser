# Teacher proposal correction implementation plan
> **For agentic workers:** Use superpowers:executing-plans inline. Each task follows RED/GREEN and independent review before publication.
**Goal:** A rejected teacher proposal gets one bounded, specific correction opportunity, and only verified reusable execution earns learning.
**Architecture:** Reuse BrainEngine repairReason, per-task/same-state/global budgets, failure holds and SkillCompiler. Add a small typed rejection model and bounded TaskLedger serialization; Planner projects only closed reasons and abstractsteps scoped to currentcapability/target.
**Tech Stack:** Existing dependency-free Kotlin core/JUnit, GeckoView Android app, Python/Node harnesses and existing stable signed release controller.
**Spec:** ../specs/2026-11-22-teacher-revision.md
## Constraints
Existing privacy, signing, human authentication, source and release locks, timer/uploader/review and all testgates remain. No provider credential reads or new cloud deployment.
## Review focus
- Old taskbudgets/same-state caps and global auth/budget failures never gain retries.
- Duplicate proposals with changed rationale/confidence never execute or earn credit.
- Changed page or selecteditem must not inherit unrelated rejection feedback or stale bindings.
- Rejected/corrupt feedback cannot persist/export arbitrary text, protected roles or oversized payloads.
- Durable request counts and correction state survive reload without extra requests or false mastery.
## Tasks
- [x] Write black-box regression: rejected reply then corrected live-target plan completes and compiles; currentcode mustfail.
- [x] Add scoped, bounded, stricttyped rejection serialization/projection tests before implementation.
- [ ] Minimal engine/planner/Curriculum fix; rerun focused and entire core suite, address all failures.
- [x] Prove persisted teacher-free replay on a different syntheticexample, plus negative/duplicate/no-effect/controlchange/auth/budget cases.
- [ ] Run fullrequired source/Android/City checks; request independent exactsource review.
- [x] Verify actual supported AI connection without reading secrets; distinguish real teacher result from scripted tests.
- [ ] Publish only through existing verified signedpipeline once all gates pass; guarded serverrollout, retainmemory, verify reportthroughconnector and handoff existingreview. No phoneinstall.
