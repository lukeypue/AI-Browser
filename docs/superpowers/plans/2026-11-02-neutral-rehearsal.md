# Neutral rehearsal scheduling implementation plan

> Execute inline using executing-plans; independent whole-branch review before publication.

**Goal:** Stop learned scrolling rehearsals with no new results from consuming every retry visit, while retaining mastery and failure memory.
**Architecture:** Extend existing typed neutral-scroll evidence and durable bounded retry schedule to completed curriculum items. Selection already checks readyAt for rehearsals. No reward, verifier, teacher quota or browser access changes.
**Tech stack:** Kotlin JVM core, Android, existing Python release controller.
**Spec:** Owner request to diagnose the 60-minute test and fix slow learning, plus docs/BRAIN_DEVELOPMENT_PRIORITY.md.

## Constraints
Preserve signing, human boundaries, privacy, running services and uploader; only normal fast-forward stable publication after all gates. Source base d6012a984cfdb86f8ab43dc74c883816742efbe6.

## Review focus
Completed rehearsal cadence, incomplete curriculum with absent pending controls, genuine progress clearing the delay, reload/clock rollback, and real failure/uncertain observation not earning neutral scheduling.

## Task 1
- [ ] Add real-engine tests to NeutralScrollLearningTest for a completed scroll lesson alongside a missing sort lesson: repeated visits at 0,30,60,90 seconds admit neutral rehearsal only at 0,30,90 after durable reload; preserve completedAt and strategy failures. Test genuine new results at expiry clear scheduling. Test one scheduler LearningSession visit creates only one neutral rehearsal rather than replaying it on a later immediate visit.
- [ ] Run focused tests; verify the new assertions fail for the completed-item exclusion, while existing tests pass.
- [ ] Remove only the unfinished-item exclusions in BrainEngine neutral evidence and Curriculum.recordAttempt. Keep all safety/uncertainty guards and 30-to-300-second bounds.
- [ ] Run focused and full suites, Android build/lint, runner/receiver/publisher and browser checks; run City preflight with unchanged frozen exams.
- [ ] Obtain actual Claude critique and fresh independent review, address important findings with RED/GREEN checks.
- [ ] Commit, publish through the locked expected-head stable pipeline, verify source/hash/signature. Do not install on phone. Coordinate results with existing review.
