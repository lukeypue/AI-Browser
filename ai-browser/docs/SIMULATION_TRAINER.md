# Offline Site Brain Simulation Trainer

This MVP starts from 7.2.3 (code 88). It runs the existing Kotlin BrainEngine,
SpsParser, TaskPolicy, grounding, Executor, Verifier, Memory and SkillCompiler
against an independent stateful marketplace Renderer. It does not replace
GeckoView or BrainService. `src/lab` is excluded from the production core jar.

## Run

From the Android project directory (`ai-browser/` inside the repository), with
JDK 17 and Gradle 9.3.1:

```sh
gradle -p brain-core test simulationExperiment
```

The report is `brain-core/build/reports/brain/simulation.json`. The experiment
uses 1,200 training episodes, 24 fixed evaluation scenarios and 24 separate
transfer scenarios. It evaluates both sets with clean memory before training,
then repeats both from frozen trained memory. Every evaluation episode gets
its own storage fork. Training uses negative seeds; evaluation uses positive
seeds. An explicit assertion prevents overlap. Scenario manifests and the
tested engine/lab source digest are included in the report.

## What is learned

A deterministic teacher demonstrates typing a query and clicking Submit using
visible semantic roles. It cannot access the oracle, catalog, seed or fault
flags. The existing verifier must confirm the actions and the program contract;
the existing compiler then produces a parameterized search procedure. Failed
demonstrations cannot create mastery. After the procedure has two verified
successes, demonstrations stop. No external AI service is used.

The search curriculum promotes after batches of ten episodes with at least
85% verified search completion, no unsafe actions and no false verified claims.
Levels add moved controls, synonyms, popups, absent filters, delays and stale
controls. Level nine uses a separate synthetic host in evaluation. These levels
are stress buckets of one scenario generator, not nine independent real website
designs. Category controls are exposed, but category navigation is not a scored
lesson yet. Full-task evaluation continues to score filters, pagination, detail
evidence, useful partial matches and safe exit independently of the curriculum.

## Objective scoring

The oracle checks the final marketplace state and catalog truth rather than
trusting DONE or the verifier. Returning the wrong model, an over-budget price,
an over-limit mileage, or a falsely verified axle ratio fails an episode. Missing
axle evidence must remain UNKNOWN. Available matching 3.73 detail evidence must
be SAT. A different stated ratio may remain conservatively UNKNOWN or be
VIOLATED, but may never be SAT. Partial matches preserve all hard criteria.

Permanent broken/blank/error/loading scenarios must terminate within budgets;
authentication must require a human. Recoverable failures are counted only if
actually injected. The report includes success, recall, stuck rate, recovery,
actions, renderer commands, repeated state/action pairs, teacher demonstrations,
real teacher calls, external cost and regression pairs. Repeated pairs can also
include legitimate repeated operations and are a diagnostic, not a loop proof.

## Production corrections reproduced by the lab

- A filled search box on HOME no longer counts as submitted results.
- A filter sheet that lacks a requested control no longer causes endless
  reopening; hard constraints are still checked on cards and details.
- A stale non-commit refusal triggers fresh observation and semantic grounding,
  with at most two retries. Document-local IDs are reset for STALE_DOCUMENT;
  STALE_TARGET excludes the refused ID. Host/authentication, exact listing
  identity, strict learned contracts and current effect interlocks still apply.
  Commit refusals are never retried.

## Boundaries and next acceptance gate

No simulation memory is imported into Android. Live hosts retain their existing
memory and evidence. No foundation-model weights are trained. This experiment
cannot establish real teacher-call savings, HTML extraction quality, GeckoView
behavior, phone/background stability or live-site transfer. It specifically
tests procedural learning, not independent discovery of a whole unfamiliar site.

Next: add an HTML renderer backed by the same hidden state; validate extraction
and disabled/delayed controls; add permanent held-out site families; then run
bounded read/search/filter trials on the four requested real sites. Require live
evidence before importing a synthetic procedure or claiming level-ten mastery.
Keep seller messages, purchases, submissions and account actions under the
existing PREPARE/COMMIT grant architecture.

Rollback: tag `rollback/site-brain-7.2.3-simulation-base`, revision
`38eccb4cf702002fea0893c33c6c1a6d5f51bde6`. This feature branch preserves app
versioning, updater publishing, signing, permissions and foreground-service code.
