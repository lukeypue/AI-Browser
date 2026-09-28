# AI Browser: faster learning and stronger AI
Research and engineering recommendation — September 28, 2026

## Decision

Build on the Claude rewrite. Give the existing Site Brain a complete **teach → execute → verify → remember → reuse** loop.

The app can use a strong external model for difficult reasoning, while its local Brain stores reliable procedures and carries them out cheaply. Browsing overnight can improve that procedural memory; it does not, by itself, retrain the underlying OpenAI or Anthropic model. General intelligence comparable to a frontier model is not a realistic outcome of accumulating browser logs. A much more reliable specialist for KSL Cars, KSL Classifieds, OfferUp, and Facebook Marketplace is a realistic engineering goal.

The highest priority is making useful experience count. Increasing browsing time or AI calls before repairing execution, verification, and reuse would feed more noise into learning.

This report contains completed research, diagnostic analysis, and a proposed implementation design. It does not represent a new APK or implemented upgrades.

## What the actual evidence says

Inspected repository: `lukeypue/AI-Browser`, branch `ai-browser-v6-deep-search`, pinned commit `f628c17291f2166deb1b1100347c7391f97653a2`.

The active build extracts `AI-Browser-Site-Brain-v3.zip`; the older source at repository root is not the full release source. I inspected the archive's Kotlin/JavaScript and the release workflow modifications separately.

Diagnostics:

- `ai-browser-diagnostics-1790578578.ndjson`: 7,994 records over 72.68 minutes, from September 27 at 11:43 p.m. to September 28 at 12:56 a.m., America/Denver.
- `ai-browser-diagnostics-1790578585.ndjson`: 6,000 records; 5,993 exactly overlap the larger export. These are overlapping windows, not two independent experiments.
- The exports do not identify an installed build reliably enough to attribute every failure to the newest commit.

| Larger export | Count | Meaning |
|---|---:|---|
| No-target failures | 7,308 | 91.42% of all recorded events; this is an event share, not a measure of CPU time |
| Step events | 7,698 | Includes repeated failed attempts |
| Steps labeled VERIFIED | 108 | Step verification is not proof of complete task success |
| Repeated FACET_APPLY missing targets | 4,081 | Biggest recorded failure pattern |
| Repeated LOAD_MORE missing targets | 2,377 | Second biggest pattern |
| Site-version-change messages | 177 | Frequent invalidation/shadowing of learned bindings |
| Finished tasks | 66 | 57 labeled DONE, 9 FAILED; DONE currently also covers budget exhaustion |

Facebook did receive checks: 18 step events across three failed tasks, with six reported planner calls. Five of its steps were labeled VERIFIED, all navigation actions. This export shows failed Facebook work, rather than no Facebook work.

### Source findings

| Finding | Evidence in inspected source | Consequence |
|---|---|---|
| Failed activity refreshes the watchdog | `BrainService.engineEvents.step()` updates `lastProgressAt` regardless of status; status messages also update it | A noisy failure loop looks alive |
| Missing targets evade normal accounting in the archive | `BrainEngine.runProgramStep()` logs them without incrementing the action budget or consecutive-failure count | Repeated attempts can consume the task's long wall-time allowance |
| Learned procedures lack a direct reuse path | `SkillLibrary.applicable()` exists but has no execution call site; `TaskPolicy` chooses fixed built-in IDs | Saving a compiled skill does not make normal execution select it |
| Planner receives skill descriptions, not executable bodies | `Planner.proposeProgram()` includes IDs, intents, and parameters | It must reconstruct procedures instead of reliably invoking remembered ones |
| Curriculum can award credit too early | `Curriculum.recordAttempt()` credits any verified step from a skill | Opening a filter drawer can look like mastering the filter |
| Program completion lacks final skill-postcondition checking | `finishProgram(success=true)` awards skill success when the cursor completes | A program can earn success without proving its declared overall goal |
| Different constraint values collapse into one parameter | `SkillCompiler.abstractSteps()` maps matching constraint values to `$value` | A learned price-plus-mileage procedure cannot safely generalize both independently |
| Compiler preconditions are overly broad | Compiled planner skills receive a fixed broad set of page types | Procedures may appear usable in the wrong situation |
| Drift detection is page-script based | `SpsParser` hashes the observed script list; `trackSiteVersion()` shadows bindings when it changes | Different page bundles may resemble a redesign; this is a hypothesis consistent with the log, requiring fixtures |
| Planner state accounting mixes observation and call state | `lastSpsHash` is assigned before `askPlanner()` compares it | The intended per-state call limit needs its own previous-planner-state field |
| Future-step planning is restricted to today's visible roles | `parseProgram()` rejects required roles absent from the initial page | A valid plan to open a drawer and then fill a newly revealed field can lose later steps |
| Task outcome and task termination are conflated | Wall/action limits and some recovery exits use DONE | Progress totals can overstate achievement |

The live-page cause of every misclassification is not established. For example, a log labels a sound control as a price facet; that is a useful fixture target, not sufficient evidence to prescribe a specific selector.

### Release delivery blocker

At the pinned commit, GitHub reports the main release workflow failed. A separate “Build Claude Site Brain v3” workflow succeeded, but that workflow builds the archive with only initial fixes and uploads a 7.0.0 debug artifact. Its green status does not validate the newer release patches.

Local YAML parsing independently reproduces a syntax error in `.github/workflows/build.yml` around the inserted curriculum block (lines 134–137). The commit describes 7.0.9 and sets versionCode 79, while the workflow's versionName remains 7.0.8. Therefore the newest commit must not be treated as a verified delivered upgrade.

Do not overwrite the frozen Claude baseline. Move the active release source into normal tracked files, preserve the archive as a historical reference, and test/build/package the same source tree.

## What the research supports

The following are primary sources, not promises of equivalent gains in this app.

| Research | Finding relevant to this project | Practical use |
|---|---|---|
| [WebCoach, November 2025](https://arxiv.org/html/2511.12997v1) | Persistent experience summaries and selective coaching improved browser-agent performance without retraining; one reported setting rose from 47% to 61% on WebVoyager | Store compact verified experiences and retrieve them at the point of need |
| [Agent Workflow Memory, September 2024](https://arxiv.org/abs/2409.07429) | Reusable workflows improved web-navigation performance and reduced steps in the tested settings | Save parameterized procedures that execution can actually call |
| [Go-Browse, revised March 2026](https://arxiv.org/abs/2506.03533) | Structured graph exploration reused knowledge across collection episodes | Choose specific missing capabilities instead of repeating homepage searches |
| [Trajectory-Informed Memory, March 2026](https://arxiv.org/html/2603.10600v1) | Structured strategy, recovery, and efficiency tips improved held-out AppWorld outcomes | Learn from recoveries and failures as well as successes, with provenance and applicability |
| [GEPA, revised February 2026](https://arxiv.org/abs/2507.19457) | Reflection on execution traces can propose and evaluate useful prompt improvements | Optimize teacher prompts offline against fixed tests |
| [ContinualSkillBench, August 2026](https://arxiv.org/abs/2608.03874) | Explicit skill libraries did not consistently outperform ordinary contextual adaptation; weak systems accumulated fragmented skills | Measure useful reuse, prune poor skills, and keep a no-memory comparison |
| [Anthropic context engineering, September 2025](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) | Relevant, compact context and well-defined tools matter more than indiscriminate context accumulation | Send the current goal, page state, recent outcomes, and a few relevant memories |
| [Anthropic agent evaluation, January 2026](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents) | Agent evaluation should check actual outcomes and use repeated trials, with capability and regression suites | Grade results independently of the agent's success claims |
| [Claude computer-use documentation, accessed September 28, 2026](https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool) | The application executes model-requested tools and returns observations, including screenshots | Add a bounded visual fallback through the existing browser adapter |
| [Anthropic multi-agent research engineering, June 2025](https://www.anthropic.com/engineering/multi-agent-research-system) | Parallel agents help independent research, but shared context/dependencies and token costs complicate coordination | Use one browser controller; reserve another model for selected difficult reviews |

These sources support the proposed mechanisms. Their benchmarks do not establish how much faster this Android app will learn.

## Recommended architecture

Preserve one browser owner and the existing Browser/Renderer → BrainEngine → Verifier → Memory separation.

1. **Local executor:** loads a proven, applicable procedure and binds current inputs.
2. **AI teacher:** handles a new control, an ambiguous state, or a failed procedure. Returns a short typed plan and its expected outcome.
3. **Verifier:** checks the resulting browser state and the complete goal.
4. **Memory:** stores procedures, semantic site maps, and context-specific failure lessons.
5. **Curriculum scheduler:** chooses the most valuable missing capability.
6. **Offline improvement runner:** compares proposed changes against held-out tasks before promotion.

The teacher can be a capable OpenAI or Anthropic model supported by the user's configured account. Do not assume model names hard-coded in the archive are available or optimal. Log the actual resolved model and test it on the same task set. No purchased service or model-weight training is required by this research.

### 1. Make progress measurable and stop repeated mistakes

Maintain separate timestamps for renderer heartbeat, successful observation, and meaningful goal progress. A log line, planner answer, URL change, or repeated navigation cycle must not automatically renew the goal-progress timer.

Count all attempted decisions, including grounding misses. Use a failure key scoped to host, capability, page type, relevant control state, and action. Exclude changing listing contents from that key.

Proposed initial policy, to tune in replay and on the phone:

- After two equivalent failures in unchanged relevant state, block that action and request one repair.
- Permit one additional repair only after a materially different observation or approach.
- End or yield after the configured no-progress deadline even if the planner is producing valid JSON.
- Persist negative evidence across tasks. A new task ID must not immediately erase a known dead end.
- Distinguish SUCCEEDED, PARTIAL, BLOCKED, FAILED, and BUDGET_EXHAUSTED.
- Preserve partial results; do not report an exhausted learning task as a mastered capability.

Use a monotonic runtime clock for deadlines. For async renderer replies, bind each request to task, host, document, and generation IDs; reject late replies from the previous task. Isolate KSL Cars and KSL Classifieds while retaining approved shared sign-in behavior.

### 2. Require full outcome verification

A price-filter success should prove that the requested limit is active, using the applied control/chip/URL state and independent checks of readable result prices. Opening a panel or changing arbitrary result cards is insufficient.

Search success should prove the intended query took effect. Detail-opening success should match the intended item. Scrolling success should establish new relevant results, not only changed geometry.

Introduce one skill-completion event only after all required steps and final postconditions pass. The curriculum consumes that event. A human demonstration enters memory as a candidate until replay proves its outcome.

Separate “observed,” “attempted,” “verified once,” and “reliably reusable.” Preserve UNKNOWN when evidence is absent.

### 3. Make remembered procedures usable

Add a selection stage before planner fallback:

- Match capability intent, host/context, page state, available roles, and parameter schema.
- Rank proven candidates by applicable success history, recency, latency, and cost.
- Execute the best eligible procedure; verify again on this run.
- Demote or repair a failing procedure instead of endlessly selecting it.

Represent price and mileage with distinct parameters, such as `price_max` and `mileage_max`. Never merge all constraint values into a generic parameter.

Store goal, typed steps, parameters, preconditions, final postconditions, schema version, evidence counts, provenance, failure conditions, and last verified time. Keep per-site bindings separate from generic skill intent. Treat cross-site transfer as a candidate requiring local validation.

Proposed promotion rule: one full verified execution creates a candidate; three successful executions across at least two input sets and two task sessions allow normal reuse. This is an engineering starting point, not a statistical guarantee.

### 4. Teach one useful capability at a time

Replace recurring mixed searches with a small dependency-based curriculum:

| Stage | Training objective |
|---|---|
| Search | Apply a query and confirm relevant results |
| Individual filters | Price, mileage, make, model, year, location |
| Dependencies | Select make before model; open a drawer before setting fields |
| Combinations | Apply two or more filters and confirm each survived |
| Navigation | Sort, paginate/scroll, open details, return without losing filters |
| Recovery | Dialogs, absent controls, delayed pages, stale targets |
| Generalization | New query values, page layouts, and later sessions |

Select tasks using value × uncertainty × chance of making progress, discounted by cost. “Unsupported,” “not present here,” “temporarily blocked,” and “not learned yet” are different states.

Keep a fair allocation across all four requested sites. Healthy learning can stay on a site for a useful stretch; repeated failure or sign-in requirements should checkpoint that site and let eligible sites continue. When all sites are blocked or complete, enter a waiting state instead of spinning.

Remember ordinal/curriculum state across visits, and retain occasional regression checks of mastered capabilities.

### 5. Give the teacher enough evidence to help

Send a compact packet with the intended outcome, current page type and host, available controls, active filters, a few recent attempts, and relevant failure/success memories.

Return structured actions with expected outcomes and applicability conditions. Validate each step against the live page when it executes, so future controls revealed by earlier actions can be handled. Do not weaken execution boundaries to accommodate a plan.

If structural information cannot distinguish controls, request a limited screenshot/crop through the browser adapter. Redact sensitive content before external use; exclude auth/challenge pages. This is an optional fallback, not a requirement to upload every browsing screen.

Use a single strong teacher initially. A second configured model can review a persistent failure or proposed reusable procedure. It should not drive the same browser concurrently, and model agreement is not a substitute for browser evidence.

### 6. Improve the AI workflow between sessions

At an idle boundary, consolidate completed redacted episodes into:

- successful procedures;
- specific failure conditions and repairs;
- shortcuts that preserved correct outcomes.

Deduplicate, retain evidence, remove contradicted memories, and scope changes to the affected capability. Do not discard an entire host's knowledge simply because a page loaded a different script bundle.

Use GEPA-style reflective prompt search only after a reliable evaluation set exists. Candidate prompt/memory changes are tested separately and promoted only when they improve measured results without regressions. Live self-modification of the verifier, action permissions, or evaluation answer keys is excluded.

Later, a curated set of verified examples may support distilling a cheaper model. Actual weight fine-tuning is a separate training project; it is premature while labels and delivery are unreliable.

## The Expedition example

For “Expedition under $8,000, under 150,000 miles, with a 3.73 axle”:

1. Apply and verify the searchable filters.
2. Check visible listing fields and descriptions.
3. Return confirmed matches where evidence exists.
4. Preserve useful candidates meeting price/mileage while marking axle ratio UNKNOWN.
5. Explain exactly what remains unverified; a door-sticker photo may resolve it later.

Do not invent 42 matches or assume 3.73 from a trim level. If no actual candidates exist, report that honestly and show clearly labeled alternatives when available. A scarce attribute must not trap the learner in endless retries.

## Implementation order and acceptance tests

| Order | Change | Required proof |
|---|---|---|
| 0 | Canonical release source and working build | Workflow parses; unit tests and packaged APK use the same source; embedded version and release metadata agree |
| 1 | Progress supervisor and task isolation | Repeated missing Apply/Load More terminates within budget; failed events cannot refresh meaningful progress; late replies cannot cross tasks/sites |
| 2 | Whole-skill verification | Opening a price panel does not complete price learning; all-optional/no-op programs earn no success |
| 3 | Direct learned-skill selection | A successful teacher-taught repair survives restart and solves a changed-input task without another planner call |
| 4 | Correct parameterization and preconditions | Price and mileage stay independent; wrong-host/wrong-page skills are rejected; future-step targets are validated at execution |
| 5 | Targeted curriculum and fair rotation | Mastered tasks stop dominating; absent capabilities are classified; all eligible sites receive work; blocked sites do not stop the entire overnight session |
| 6 | Teacher context and visual fallback | A selected perception failure is solved with grounded observations; failed teacher output stays bounded |
| 7 | Offline reflection and prompt comparison | Candidate beats the frozen baseline on unseen tasks under equal call/time budgets |

Keep the existing core tests and add only fixtures that exercise these concrete risks. Use synthetic or redacted page states, not account data.

### Evaluation design

Start with 24 development cases (six per site) and 16 held-out cases (four per site). Include the actual missing-target patterns, price-plus-mileage filtering, unknown axle ratio, DOM changes, stale responses, and sign-in interruption.

Run the baseline and candidate three times on equivalent resettable fixtures. Compare cold memory and warm memory. Keep held-out cases, identities, and outcomes out of prompt optimization and memory generation. Add later-session live checks because fixtures cannot prove real-site behavior.

Track:

- complete-task success and honest partial-result quality;
- verified reusable capabilities gained per hour;
- repeated-failure attempts and time without meaningful progress;
- learned-skill reuse rate;
- median actions and latency per successful task;
- actual model calls/tokens/cost per success;
- false verification and cross-task/site contamination;
- coverage of the four sites.

Initial gates: the specific loop and false-success fixtures must pass, previously passing regression cases must remain passing, and warm-memory runs must demonstrate correct reuse. Report raw counts alongside rates because this is a small benchmark. Do not promise a percentage speedup before these measurements.

The final device check should include screen-off operation, network loss, a renderer restart, and app restart with memory retained. Existing source and CI inspection cannot prove those behaviors on the user's phone.

## Work completed for this report

Analyzed both diagnostic exports with overlap checking; reviewed the archived Claude implementation and pinned workflow changes; verified GitHub run outcomes; reproduced the release YAML failure locally; researched the primary sources above; and defined the proposed architecture, priorities, and tests.

No repository files, API configuration, model weights, release versions, or installed app were changed in this research turn.

