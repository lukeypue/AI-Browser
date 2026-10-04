# Site Brain simulation trainer MVP

## Intent and current evidence
Continue release 7.2.3 / code 88, source `38eccb4cf702002fea0893c33c6c1a6d5f51bde6`.
Keep GeckoView, BrainService, semantic perception, TaskPolicy, Executor, Verifier,
SkillCompiler, SkillLibrary, per-host models, teacher budgets and PREPARE/COMMIT.
The two latest phone logs have identical SHA256; their 7.2.2 segment contains 7
PARTIAL lessons, zero new lessons, and 23 failed action outcomes over 56.36 minutes.
There are no supplied phone diagnostics from 7.2.3. This is a historical symptom,
not evidence the newest release still fails.

## Choices
1. Recommended: an offline Kotlin lab using the existing Renderer interface and
the production engine, with deterministic marketplace state and an independent
oracle. No Android dependency or paid teacher is needed for the first experiment.
2. HTML/Chromium-only training would cover extraction/rendering but costs more per
episode and introduces another browser integration. Add it after the state lab.
3. A new reinforcement-learning model would need substantially more data and phone
performance work. Procedural learning already exists and should be measured first.

## Architecture and smallest useful scope
Add an offline `lab` source set to brain-core, excluded from its production jar.
SimulationMarket implements Renderer and emits raw extractor-format observations;
SpsParser, grounding, execution, verification and learning stay real. The oracle
alone sees catalog truths and scenario faults. Neither Brain nor teacher sees
expected answers, difficulty, seed, hidden listing attributes or fault flags.

Seeded scenarios cover home/category/search, numeric price and mileage, pending
filters and apply, result cards, pagination, detail evidence, moved/reordered
controls, renamed labels, dismissible popups, delayed settle, omitted filters,
broken search, blank/error pages, stale targets and human-only interruptions.
Catalog records include genuine synthetic matches, price/mileage violations,
wrong models, stated rare evidence and absent rare evidence. Missing rare facts
remain UNKNOWN; useful candidates retain the hard constraints.

Progressive difficulty levels 1–9 are synthetic. Level 10 is a separate future
live acceptance gate; synthetic success never unlocks it. Promotion needs at
least 10 episodes, >=85% verified search completion, zero unsafe actions and zero false
verified claims. No arbitrary success seeding is permitted.
Promotion in this MVP measures the search lesson only; it does not establish
mastery of the full marketplace task or unlock real-world validation.

## Experiment and contamination controls
First preserve the rollback tag and source digest, then run existing regression
tests. Run fixed held-out scenarios before training with a clean memory snapshot;
each evaluation episode gets a fresh writable fork of that frozen snapshot so
engine writes cannot teach other evaluation episodes or training. Train on
disjoint seeds; transfer additionally uses a different host. Rerun the identical evaluation and a separate transfer set
using a frozen post-training snapshot. Persist paired rows, manifest hashes,
source revision, training count and elapsed wall time.

A deterministic laboratory teacher may supply a two-step search demonstration
using only visible search/submit roles and the user query. Each step must verify,
then the existing SkillCompiler must abstract values into parameters. Count these
supplied demonstrations separately from actual planner transport calls. Record
that no external model was evaluated; dollars and external tokens are zero for
this local experiment. Do not claim neural weight training or production savings.

Scoring independently checks expected safe termination for interruptions/failures,
hard constraints on returned verified/partial items, rare-fact truth, recall,
filter state, recovery after injected faults, action counts, repeated state/action
pairs, teacher calls/demonstrations, compiled-skill reuse and bounded exit.
DONE status by itself never earns success. Report no improvement honestly.

## Prerequisites and risks
An unsent search value currently makes TaskPolicy.queryApplied true even on HOME.
Reproduce before fixing: require a RESULTS page before accepting query evidence.
This narrow fix prevents draft text from being treated as completed search.
The lab also reproduced repeated reopening of a filter sheet when its requested
filter was absent, and abandonment of a learned procedure after a stale target
refusal. Mark absent sheet controls as unavailable for this task (continue hard
checks on cards/details), and re-observe/re-ground stale non-commit actions with
at most two retries. Preserve host, authentication, strict grounding and commit
interlocks. No architecture rewrite is justified before a measured experiment.

Universal roles and skills already exist; per-host vocabulary/bindings and task
ledgers provide the remaining layers. Site-family knowledge is not yet explicit.
Keep simulation storage separate. Never promote synthetic host success counts
to live hosts. A later import would need provenance/quarantine and real-site
verification; it is outside this MVP.

The initial state lab does not establish HTML extraction, GeckoView behavior,
Android background reliability, real teacher quality or live-site improvement.
Run existing browser fixtures and Android CI separately. No signing, permission,
workflow, updater or version changes are included. Prepare a debug test build on
an isolated branch; do not replace the stable updater with the offline lab.

## References reviewed 2026-10-04
- https://github.com/ServiceNow/BrowserGym — unified tasks, observations/actions and evaluation.
- https://miniwob.farama.org/ — small browser tasks suitable for controlled curricula.
- https://webarena.dev/ — realistic self-hosted web environments for later validation.
These motivate controlled environments; the choice to reuse the Kotlin Renderer
is an engineering inference from this project's existing architecture.

## Authorization
The user explicitly requested continuous investigation, design, implementation,
tests and preparation of a testable build without small-decision approval gates.
Implementation proceeds inline under that instruction.
