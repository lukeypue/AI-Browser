# Verified procedural acquisition implementation plan

> Execution: superpowers:executing-plans, inline in the existing isolated worktree; independent whole-change review at the end.

Goal: establish a measured teacher-assisted procedural acquisition through the actual engine, without changing the production app or running workers.
Spec: docs/superpowers/specs/2026-10-19-brain-frontier-screen.md.

Scope: a lab-only prerequisite-search fixture, whole-engine cold/reference/stub checks, durable frozen-memory forks, recorded real-teacher request/replay, held-out teacher-disabled controls, and explicit report labels. Do not change production curriculum, identities, timers, signing or release gates.

## Task 1: Whole-engine frontier evidence
Files: lab/FrontierScreen.kt; test/lab/FrontierScreenTest.kt; lab/FrontierScreenMain.kt; core build.gradle lab task.
- [x] Write behavior tests for cold-vs-retained-vs-ablation attribution, persistence, independent oracle, no-op and human negatives.
- [x] Run with minimal nonimplemented report and observe the two intended assertion failures.
- [x] Implement a synthetic search prerequisite using existing TAB/SEARCH_BOX/SUBMIT and existing URL_CHANGED/value/search postconditions. Audit the complete cold engine; if it already solves it, classify SOLVED rather than crediting acquisition.
- [x] Verify parameterized compilation, two source verifications, eight held-out forks, ablation preserving other state, no false credit and unchanged frozen snapshot.
- [x] Run focused and full standalone core tests.

## Task 2: Authenticated teacher request and replay
- [x] Capture the actual PlannerClient request from the same fixture, not a hand-written answer.
- [x] Use the existing protected Claude CLI, with tools disabled, on only that synthetic capsule.
- [x] Feed its recorded typed response through actual Planner.parseProgram/BrainEngine.
- [x] Independently verify frozen teacher-off held-out and ablation results; restart the JVM and reload the persisted experiment memory.
- [x] Report scripted readiness separately from real model-assisted acquisition; never count it as real-site mastery.

## Task 3: Review and preservation
- [x] Obtain actual Claude critique of diff and measured results.
- [x] Obtain independent code review; address proven findings test-first.
- [x] Verify worker health and coordinate with the existing review only.
- [x] Preserve release blocker; no APK release or phone install.

Review focus: fixture oracle independence, warm-up leakage, positive-vs-negative ambiguity, request/response provenance, retention after fresh JVM, and truthful reporting of unresolved stages.

## Execution rulings and verified outcome

The eight-case screen is semantic rebinding within one synthetic prerequisite state machine, not independent structural-family or real-site mastery. The coach remains controller-driven, not unattended production integration.

Actual model request history is preserved. One real candidate reached the oracle but an ambiguous trailing wait prevented compilation. A later candidate confused prior renderer history with the current page; AI review corrected this using the source-defined convention that omitted selected means false. The final real response was executed unchanged from a clean preserved failed-attempt snapshot. Exact sent/replayed engine request bytes and full request/raw-response/program hashes were verified. No failed proposal was edited into a success.

Independent review found ordinary-task and draft-negative gaps; both reproduced RED and were fixed. Claude review found source-chain and partial-credit gaps; three more tests reproduced these RED before fixes. Independent review then caught request JSON entering BrainStorage; the isolation test reproduced it RED, and artifacts moved to a separate exchange directory. Frozen memory is scanned for capsule/query contamination.

Final whole core suite: 470 tests, zero failures/errors/skips. Actual model-assisted acquisition: one compiled procedure; held-out learning and ordinary search each retained8/cold0/ablation0; zero evaluation teacher calls/false credit. A failed learned procedure has zero host successes and one failure. Fresh JVM retention and exact source-chain proofs are stored in the private learning-acceleration-20261020 evidence folder. Existing Android process-death blocker remains; no production integration, APK publication or phone installation.
