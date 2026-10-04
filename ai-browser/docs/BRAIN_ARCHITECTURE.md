# Site Brain v3 — Architecture

This document is the map of the brain. Read it before changing `brain-core/` or the
WebExtension. It replaces the earlier `docs/superpowers/*` plans, which describe the
fingerprint-era design.

## 1. What the brain is

A persistent, tool-using, partially observable agent for marketplace websites, built as a
**planner / executor / verifier loop over a Semantic Page State**, with a deterministic skill
engine in front of an external reasoning model and a verifier that is the sole writer of
success/failure into memory.

```
                   ┌──────────────────────────── memory (per host, no content) ───────────────────┐
                   │ site model · bindings (Beta stats) · facet vocabulary · failures · curriculum │
                   └────────────▲───────────────────────────────────────────▲──────────────────────┘
                                │ read                                      │ verifier writes only
   Renderer ── raw JSON ──▶ Perception ──▶ SPS ──▶ Policy ──▶ Skill engine ──▶ Executor ──▶ Renderer
  (GeckoView +               (Kotlin,             (deterministic   (ground roles    (interlocks,
   content.js)                redaction)           state machine)   to elements)     settle, observe)
                                                       │ miss                                │ (s, a, s')
                                                       ▼                                     ▼
                                                    Planner (external model, ≤4 KB) ◀──── Verifier
                                                    returns a *program*, never a click
```

## 2. The units

| Unit | Definition | Why |
|---|---|---|
| **SPS** (`model/Sps.kt`) | page type + typed affordances (role, facet key, effect class, features) + collections keyed by stable item keys + active constraints + settle state | Identity for a *situation*, not a DOM. A React re-render produces the same hash; a new filter state does not. |
| **Action** (`model/Actions.kt`) | kind + abstract target (`AffordanceRef`) + `expect: [Postcondition]` + effect class from perception | Typed pre/postconditions; effect class can never come from a plan. |
| **Postcondition** | `ResultsChanged`, `NewResults`, `EndOfResults`, `ConstraintApplied(k,v)`, `PageTypeIs`, `DetailMatches`, `TextExpanded`, `DialogClosed`… and `AnyOf` | The only reward. "Clicked" is never an outcome. |
| **Skill** (`model/Skills.kt`, `skills/BuiltinSkills.kt`) | program of `Step`s over roles + canonical facet keys, with `Opt` steps, params, pre/post, per-host Beta stats | Selectors never appear in skills; one skill serves inline facets and drawer facets. |
| **Binding** | (host, page type, role, facet key) → feature vector + name hints + Beta stats + site version | The per-site answer to "which element plays that role". A bounded bonus for grounding, never a short-circuit. |
| **Site model** (`model/SiteModel.kt`) | page types seen, affordance graph edges with verified counts, facet vocabulary, endpoint effect classes, bindings, failures, curriculum, calibration (Brier) | The semantic world model; survives ordinary page changes; verified failures retire or quarantine bindings. |
| **Site profile** (`profile/SiteProfiles.kt`) | data: hosts, start/login/search URL templates, vocabulary seeds, quirks, pacing | Replaces per-site "mini brain" code. |
| **Task ledger** (`model/Ledger.kt`) | goal, phase, program cursor, verdicts with evidence, visited keys, grants, checkpoints | Persisted before every action and after every verification; survives renderer and process death. |

## 3. The loop (`engine/BrainEngine.kt`)

```
loop until ledger.done or ledger.blocked:
  s = observe()                                  # SPS via Executor → Renderer → content.js
  collectCards(s)                                # RESULTS pages feed the verdict table
  if s.isHumanOnly: status = NEED_HUMAN; return  # auth wall / challenge: never explored, never sent to a model
  if a program is in flight: run its next step
  else decision = TaskPolicy.decide(goal, s, ledger)
       RunSkill  → bind params, start program
       Navigate  → one-step program
       AskPlanner→ bounded local recovery, then planner.proposeProgram (shared budget, cooldown) or bounded fallback
       EvaluateDetail → evidence extraction on a DETAIL page
       Grant     → status = NEED_GRANT (message preview)
       Finish
step execution:
  ground(step)  → Ready / Skip (optional, holds already) / Missing
  Executor.execute(action)  → interlocks → renderer.act → waitSettle → observe s'
  Verifier.verify(action, s, s')  → VERIFIED / FAILED / AMBIGUOUS (re-observe once) / HUMAN_NEEDED
  memory.record(binding, edge, calibration, episode)   # the only place stats change
  FAILED → re-observe and resolve a fresh live target (≤2 retries); never replay stale alternates
```

**TaskPolicy** (`engine/TaskPolicy.kt`) is a small phase machine for `FIND_LISTINGS`:
`START/SEARCH` (direct search URL if the profile or memory knows one, else the `search`
skill, else a category link) → `CONSTRAIN` (one filterable constraint at a time, ≤2 attempts
each; choice facets accept the closest available option) → `COLLECT` (scroll / load more /
next page until enough candidates or `EndOfResults`) → `INSPECT` (open items that need a
detail read, expand, evaluate, back) → `DONE`. `PREPARE_MESSAGE` runs open → composer → fill →
`NEED_GRANT` → `commit_send` only with a grant. Zero recognized listings and unresolved
required detail checks finish as PARTIAL, never as completed inspection.

A model request for human help on an ordinary page ends the attempt without creating a
permanent sign-in hold. Observed auth/challenge pages require human help; an incidental
offsite transition during training ends that lesson without inventing a sign-in request.
Explicit Start rechecks saved human-review requests through the normal landing
safety checks; automatic idle retries cannot clear them. Daily challenge limits remain.

`SemanticDiagnostics` exports typed failure codes, page types, control/result counts and
expected/observed hosts. It excludes URLs, query values, names and page text. Task summaries
separate newly completed lessons from repeated verified skills.

## 4. Perception (`perception/`)

`content.js` extracts *raw* structure (elements with attributes, regions, cards, signals,
settle state) and redacts PII at the source. `SpsParser` assigns semantics in Kotlin so they
are testable on the JVM: `RoleClassifier` (rule table over tag/ARIA/lexicon/region/context),
`Vocabulary` (canonical facet keys), page type classification, active constraints from facet
values, URL query keys and path-encoded facets, the semantic hash, and the site version
signature (hash of visible control roles, facet keys and tags). This signature describes a
page shape, not a deployment: a drawer opening or the last page losing its Next button
must not invalidate unrelated working bindings.

Privacy rules enforced here: no password/hidden/file inputs; on auth walls and challenges no
names, values, titles or items; emails/phones/VINs/addresses/long digit runs redacted in
snippets; instruction-like sentences neutralised in anything that goes to a model.

## 5. Memory (`memory/`)

- `Memory` — facade over `BrainStorage` (atomic file writes). Tiers: `site/<host>`,
  `skills`, `episodes/<host>` (≤2000, content-free), `ledger/<id>`, `labels/<host>`.
- `BetaStat` — Beta(α, β) with daily decay 0.98; routing uses posterior mean *and* count.
- `Consolidation` — retire bindings (p<0.3 after 5 trials, 60 days unseen), shadow weak legacy version bindings, prune failures/edges, dedupe compiled skills, budgets, **Brier > 0.25 ⇒ quarantine**.
- `FailedStrategies` — overnight practice remembers failed procedure shapes and AI repair attempts
  across task ledgers. Two unsuccessful attempts defer that shape for five minutes; changed
  controls and different procedures remain eligible. At most 64 content-free records per host.
  Site rotation still retries after one minute. A verified procedure clears its failure record.
- `SkillCompiler` — verified local/planner programs → `COMPILED` skills (values → params);
  explained human demonstrations → `DEMONSTRATED` skills.
- `Curriculum` — per-site goals selected from observed live opportunities. Unavailable controls
  wait without creating a task or a success. An idle results page with no unfinished
  lesson targets may try one different practice search per probe; auth, busy pages,
  dialogs and live lesson controls prevent that recovery. One-minute probes continue; failed lessons back
  off for 1/5/15/60 minutes unless relevant live controls change.
- `SkillLibrary` — canonical program identity with independent host evidence; strong source
  evidence can admit a compatible procedure on a new host. A target failure blocks that
  host until an independently verified repair. Every action is grounded and verified again.
- `TeacherBudget` — durable reservations before actual transport: 30 requests per rolling
  hour and 120 per rolling day, shared by learning, search and demonstration explanation.
  Failed requests consume allowance. This is a request cap, not a dollar cap. Provider usage
  is recorded when returned; missing usage is distinguished from zero usage.

## 6. Safety model

| Layer | Mechanism |
|---|---|
| Executor | `COMMIT_EXTERNAL` (SEND/BUY/BID/POST/DELETE/FOLLOW/SAVE/REPORT) requires a matching, unexpired, single-use `Grant` whose preview hash equals the previewed text. TRAIN mode never types into a composer or touches commit roles. |
| Network (`background.js`) | TRAIN: block learned/heuristic commit endpoints and unknown mutations; ASSIST/COMMIT: block commit endpoints unless a grant window (≤120 s) is open. GraphQL operation names are inspected. Unknown mutation endpoints are reported for classification. |
| Input (`content.js` guard) | TRAIN: never focus composers; swallow Enter in composers; cancel submit on forms containing a composer. |
| Planner | Never receives auth/challenge states (request builder refuses); commit roles are stripped from its output; page text is wrapped as data and redacted. |
| Pacing | Per-profile action intervals with jitter; challenge count per site per day stops the learner at 3. |

## 7. Renderer (`app/.../browser/`)

- `BrainRuntime` — one `GeckoRuntime`, Firefox-default cookie behaviour, login autofill off, the bundled extension installed with `ensureBuiltIn` (bump `manifest.json` version to force re-install).
- `BrainSession` — main session + popup stack (`onNewSession` / `onCloseRequest`), prompt delegate, progress tracking, extension ports with request ids and deadlines, an off-screen `GeckoDisplay` while unattended, and `attachTo(GeckoView)` / `detachFromView()` for the human handoff — the same session, never a copied cookie. Popups are refused while unattended so the working page keeps its display.
- `GeckoRenderer` — implements `Renderer`; a lost port during an action means "the action navigated"; a lost port during observe waits for the new document once.
- `BrainService` — foreground service owning the engine thread, wake lock, notifications, watchdog (4 min without progress ⇒ recover renderer and resume), `onTimeout` for Android 15 limits, teach mode.

## 8. Evaluation

- `brain-core/src/test/harness/extract.mjs` runs `content.js` in headless Chromium on the
  fixture pages and records observations; `SpsParserTest` / `DrawerPageTest` parse those
  recordings, so extractor and perception are tested together.
- `FakeSite` (a scripted marketplace in the wire format) drives `BrainEngineTest`: end-to-end
  search → constrain → paginate → inspect → ranked evidence; login pause/resume; grant flow;
  TRAIN-mode interlock; renderer timeout recovery; ledger round trip; curriculum learning.
- `VerifierTest`, `ConstraintEvaluatorTest`, `GoalParserTest`, `MemoryTest`, `PrivacyTest`.

Metrics to add next (the harness supports them): verified success rate per page type, action
efficiency vs. oracle, transfer gain (run 2 / run 1) on a held-out profile (`craigslist`,
`ebay`, `autotrader`, `cars_com` are seeded as candidates), planner calls per task, Brier per
host, brittleness (randomised class names in fixtures).

`LocalFirstEvaluationTest` compares focused lessons, absent controls, fresh target recovery,
and compatible/incompatible held-out host reuse. These synthetic fixtures exercise the real
engine/verifier but do not establish live-site success or model quality. `OpportunityCurriculumTest`
checks that an hour of absent-control rechecks creates no lesson tasks. Provider tests use a
fake HTTP transport and do not consume paid API credits.

## 9. Optional teachers and local operation

The Android app exposes OpenAI, Anthropic, Groq, Gemini and an HTTPS OpenAI-compatible
Chat Completions endpoint. A blank model field selects an economical documented default;
explicit model selections are retained. There are no hidden model fallbacks. Local-only
mode prevents every model transport path through the shared observer and leaves deterministic
execution available. Toggling it cannot cancel a request already sent to a provider.

This update strengthens procedural memory, planning and verification; it does not train
foundation-model weights. The core stays pure Kotlin. An optional on-device model remains
a measured follow-up requiring phone memory, thermal, latency and task-success evidence.
See [the research report](research/2026-09-29-brain-research.md) for sources and limitations.
