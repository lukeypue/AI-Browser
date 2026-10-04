# Simulation Trainer Implementation Plan

> Use superpowers:executing-plans for inline implementation, with TDD and final review.

**Goal:** Measure procedural learning of the existing Brain in a controlled marketplace.
**Architecture:** Offline lab source set implements Renderer, independent scoring and frozen
memory experiments. Production code changes only for regressions reproduced by tests.
**Tech Stack:** Kotlin 2.2.10, JVM 17, Gradle, JUnit 4; no new production dependency.
**Spec:** ../specs/2026-10-04-simulation-trainer-design.md

## Global constraints
- Start from commit 38eccb4cf702002fea0893c33c6c1a6d5f51bde6; preserve rollback.
- Training and evaluation use disjoint scenarios and independent memory forks.
- Engine/verifier remain real; simulation truth never enters observations.
- No paid API calls, secrets, version/signing/workflow changes or live memory imports.
- At least 10 episodes and >=85% success with zero unsafe/false claims to advance.

## Review focus
- Draft search fields must not count as submitted search.
- Missing mileage/axle controls must not silently relax hard bounds or verify rare facts.
- Evaluation writes must not affect later cases or training.
- Broken, loading and auth states must terminate within budget without commit actions.
- A renamed/reordered held-out site must use parameterized procedures, not training IDs.

### Task 1: Search correctness prerequisite
Files: engine/TaskPolicy.kt; test engine/SubmittedSearchTest.kt.
Interface: queryApplied(Goal, SemanticPageState): Boolean.
- [ ] Add test: HOME with typed Ford Expedition returns false; RESULTS returns true.
- [ ] Observe assertion failure; require RESULTS before any query evidence.
- [ ] Run core regression suite; commit focused fix.

### Task 2: Controlled marketplace and independent oracle
Files: src/lab/kotlin/com/appgate/brain/lab/SimulationMarket.kt, Scenario.kt,
EpisodeScore.kt; test lab/SimulationTrainerTest.kt; brain-core/build.gradle.
Interfaces: SimulationMarket(Scenario): Renderer; Scenario.goal(): Goal;
EpisodeScorer.score(Scenario, SimulationMarket, TaskLedger): EpisodeScore.
- [ ] Add tests for real-engine matches, unverified axle, filter state, missing
  mileage, blank/loading/auth, no-op/stale controls, and failed-output scoring.
- [ ] Run tests and confirm missing implementation; implement deterministic state
  transitions and independent truth checks, not canned engine responses.
- [ ] Run new and existing tests; commit.

### Task 3: Frozen-memory experiment and curriculum
Files: SimulationTrainer.kt, SimulationCurriculum.kt, SimulationMain.kt; lab tests.
Interfaces: SimulationTrainer.evaluate(List<Scenario>, Map<String,String>),
train(List<Scenario>): training snapshot; experiment(): JSON report.
- [ ] Add tests for evaluation immutability/order independence, curriculum evidence
  threshold, rejected false success, persisted compiled learning and held-out reuse.
- [ ] Implement disjoint seed manifests, writable per-case forks, teacher demos
  verified by the real engine, paired baseline/post/unseen rows and honest metrics.
- [ ] Run experiment and full suite; report observed improvement or absence of it.
- [ ] Commit report and run instructions; run browser/Android checks and prepare build.
