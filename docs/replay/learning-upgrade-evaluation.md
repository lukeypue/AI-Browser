# Site Brain learning upgrade evaluation

These are synthetic replay results, not a live-site or model-intelligence benchmark.

## Reproduce

From `ai-browser/`, run `gradle :brain-core:test`. The `ReplayEvaluationTest` writes
`brain-core/build/reports/brain/replay.json`. Run the document boundary checks with
`node --test brain-core/src/test/harness/document-boundary.test.mjs`.

The local verification used Java 17, Kotlin compiler 2.2.21 bundled with Gradle,
and JUnit 4.13.2 because this workspace initially had a JRE without a JDK.
Release CI uses the project's pinned Kotlin 2.2.10 and a full JDK 17.

## Observed replay

| Scenario | Outcome | Actions | Model calls | Listings | Inspected |
|---|---|---:|---:|---:|---:|
| Cold deterministic search | DONE | 10 | 0 | 6 | 5 |
| Warm search after reloading memory | DONE | 10 | 0 | 6 | 5 |

The warm run used its learned search URL. It did not reduce action count in this
scenario. Timing is recorded in the raw report but is not a meaningful speedup
claim for this small in-memory fixture.

A separate regression trains a procedure from one scripted planner response,
reloads memory, changes the query, and verifies that the saved procedure executes
with zero planner calls. It also rejects replay on an incompatible page type.
The model is scripted in this regression; it does not measure any provider's quality.

## Regression coverage

- Missing targets consume a bounded attempt and record failure.
- Repeated verified states do not count as new progress.
- Exhausted budgets are reported explicitly, not as successful completion.
- Partial steps and no-op programs cannot become successful learned procedures.
- Price and mileage use independent parameters, including their postconditions.
- Unexpected hosts cannot populate a task's site memory.
- Replaced document ports cannot complete or cancel another document's requests.
- Actions on stale documents are refused before target lookup.
- Planner steps can reference controls revealed by earlier steps; forbidden steps
  invalidate the whole plan. All targets are grounded again before execution.
- Script bundle churn alone does not invalidate semantic bindings.
- One human-only site does not halt the next training site; all completed sites idle.
- Rare listing facts remain UNKNOWN unless supported by evidence.

## Final review and recovery verification

The independent review's six important findings have regression coverage and
fixes: extension upgrades preserve identity while advancing the bridge version;
combined filters must all remain active; consolidation preserves host-specific
procedures; unavailable selected lessons yield; deterministic failures can invoke
bounded AI repair; and fresh typed evidence can teach text expansion even when
the structural page hash stays unchanged.

After the build workspace restored an earlier checkpoint, those fixes were
restored and rechecked. Six Kotlin regressions and the extension-version guard
failed before restoration. The full local suite then passed 70 Kotlin tests and
3 JavaScript checks. The restored run used the local compiler described above;
the pinned Kotlin compiler and Android app are checked in CI before release.

Public source/workflow upload and signed release publication were explicitly
approved by the user on September 28, 2026. Release checks still require Android
CI, the existing signing certificate, exact version/commit metadata, and the
downloadable APK. Passing synthetic tests is not a claim of live-site speedup.

Minor architecture prose and plan-checkbox synchronization remain deferred.
The execution ledger records progress. Full unchanged privacy/network code and
Android process-death behavior were not independently certified. The replay
acceptance permits reuse after one fully verified taught run; stricter promotion
thresholds and broad query/detail-verifier redesign remain future work.

## Device checks still needed

Live KSL, Facebook, OfferUp and other layouts, login handoffs, background operation,
and real model latency require device validation. This release improves procedural
learning and reliability; it does not retrain a foundation model or establish parity
with ChatGPT or Claude. Vision fallback and weight fine-tuning remain future work.
