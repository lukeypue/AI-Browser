# Reading sanitized learning diagnostics

These reports describe procedural learning and verifier outcomes. They do not prove foundation-model training, real-site mastery, or physical-phone improvement from a synthetic run.

## Phone reports

The existing phone-reports/<UUID>.json and index.json remain the destinations. Schema2 gains optional fields; strict schema1 and historical schema2 remain valid. Unknown/free-text values are dropped or become UNKNOWN. No URL, query, prompt, item identity, website-content hash, cookie or credential is exported. Limits, sharing controls, queue behavior and receiver retention remain unchanged. The receiver retains reports30days; private Git history may retain sanitized copies longer.

- task_sequence identifies an engine invocation within a process, including across engine recreation;0 means unavailable/exhausted. It does not reveal a ledger ID. A resumed invocation receives a new sequence; a process restart interrupts the timed test. program_sequence counts program admissions including held proposals; a resumed program starts at1. action_sequence includes actual action retries. None of these coordinates alter stored task/site JSON.
- before_page_type / after_page_type and before_settle / after_settle distinguish RESULTS->UNKNOWN from an unobserved result. NOT_OBSERVED means there was no after observation; UNKNOWN is an observed but unclassified state. program_source is a closed category, never a skill ID.
- planner_outcome describes the proposeProgram invocation: STARTED followed by an outcome such as ERROR, REFUSED, GIVE_UP, INCOMPATIBLE, ACCEPTED_STARTED or ACCEPTED_HELD. It is not proof of an HTTP transport, provider billing, or learned success. LOCAL_REPAIR and UNAVAILABLE do not imply an AI request. Exception/rationale text is excluded.
- learning_schedule is recorded after the existing scheduler updates memory. rehearsal means the requested lesson was already complete at task entry. prerequisite_only distinguishes opening an item while trying to learn description expansion. no_progress_retries and cooldown_remaining_ms explain pacing; they award no mastery.
- pending_lessons contains at most11 typed opportunities and live prerequisites, including the existing no-progress cooldown. It carries no control values or selectors.
- strategy_key_same, when present, compares the previous content-free procedure key for the same host/capability in this engine. It is not proof of the same listing or button; keys are never exported and the transient comparison map is bounded.
- Optional summary.new_lessons, neutral_programs, held_programs, planner_errors and diagnostic_events are captured by the owning timed test before log trimming, then frozen at completion. Missing totals in older/interrupted reports mean unknown, not zero. Held totals count hold-check events, not unique targets. diagnostic_events counts engine diagnostic callbacks, not every log row. The combined trimmed flag still means some source/export rows were omitted; never infer complete history from the retained trace.

## Practice City

Existing reports/latest.json and immutable reports/run-*.json gain diagnostics only for new raw runs containing it, using export_schema_version4. Historical report bytes are preserved. Frozen scores, cases and manifests are unchanged.

The block contains exact streaming event totals for each frozen exam stage and at most8 deterministic sampled cases. Each case keeps at most6 first/last sanitized callbacks, with events_seen/events_dropped and monotonic elapsed time. Repeated-actions scores are unchanged and include successful repetitions; action_failed and neutral_programs give separate context. Stage names identify cold versus trained evaluations. The case ID, source revision/hash and manifests identify the reproducible synthetic input; samples are not a complete transcript.

A separate unscored learning_clinic exercises the description lesson's item-opening prerequisite under normal detail, unknown-after-click, no-op-click and human-only observations. diagnostic_expectation_met means the expected diagnostic difference was observed, including expected failures. It never means those failure cases were successfully navigated. The clinic has no external model or real-site data. Frozen exam traces do not cover every training episode or the Android lifecycle.

The independent publisher validates stage totals against every raw episode, sample membership and trace schemas; it rejects malformed diagnostic blocks rather than silently publishing them. All reports use the existing shared writer lock and expected-head fast-forward publisher. No new review automation is needed.

Follow-up review corrections: City retains the first item-opening action even when it falls outside the ordinary first-three/last-three window, replacing one early generic row while keeping at most six rows and chronological order. Sample diversity uses the last meaningful diagnostic code. A failed lesson-clinic control is published with a validated false finding; it does not hide the frozen exams. Whole-test lesson counts include verified learning saved before a human/grant/pause stop and do not recount it on resume. Missing program sources are NONE; unrecognized nonblank sources are UNKNOWN.
Diagnostic timings and process-local invocation ordinals vary between identical synthetic runs. Compare frozen scores and manifests for determinism, rather than requiring byte-identical diagnostic traces. Top-level UNKNOWN on lifecycle/schedule/planner rows is not an observed UNKNOWN page; only action_outcome before_page_type/after_page_type describe an observation pair. Page-derived counters retain the existing after-when-present, otherwise-before convention. Queue retries send the stored report bytes rather than re-exporting old logs.

Deadline accounting: phone whole-test new_lessons sums only newly completed curriculum IDs reported at the saved program memory checkpoint, before task completion. Task-end learning_outcome is context and is not counted twice. A closed owner rejects later callbacks. Interrupted reports with no completed end summary omit optional totals instead of inventing zero. This counter measures verifier-credited curriculum transitions, not neural training or website mastery. City aggregates its finite episode outcome totals separately.

## Typed AI repair failures

Optional schema2 planner_failure and planner_provider are closed enums. Provider is the configured preset, not proof of the remote endpoint's identity. No model identifier, endpoint, key, headers, body, prompt, rationale or exception text is exported. HTTPstatus is an integer100..599 when an HTTP response was observed; absence is unknown. planner_elapsed_ms is monotonic time spent in proposeProgram, capped at1000000ms; it is not provider latency or billing proof. Authentication means HTTP401, permission403, rate limit429, server5xx; other HTTP replies remain HTTP_OTHER. TIMEOUT/TRANSPORT/INVALID_RESPONSE/BUDGET/REFUSED/UNKNOWN are distinct typed categories. Unknown exceptions remain UNKNOWN; no message guessing. The HTTP client preserves its sanitized errors and has no added retries.

Completed phone summary failure counters are captured by the timed-test owner before trimming and freeze with it. Authentication, permission, rate, server, transport(includingtimeout), response, budget and other errors partition ERROR events; refusal is a separate planner outcome. Missing totals in old/interrupted reports remain unknown. These counts locate failed repair requests, not failed website logins or erased memory. The completion screen points to the selected AI planner connection after an authentication rejection. Changing credentials still requires secure owner action; no secret is read or transmitted by diagnostics.

City retains the first planner error and first item-opening action in at most6 event rows with internal callback ordinals, preserving repeated identical callbacks and chronology. The optional unscored planner_clinic runs the real engine against fake clients for401/403/429/503/timeout/invalidresponse/unknown/returned controls. Its8 rows are independently allowlisted; external_requests is0. A true diagnostic_expectation_met records correct identification of the injected outcome, not successful navigation or learned mastery. A false control remains a reportable finding. Frozen exams/manifests/scores, schedules and request budgets are unchanged. Independent receiver/publisher validators and the separately pinned Android-cycle validator must be deployed before app rollout.


## Verification and teacher rejection evidence
Optional schema2 fields distinguish action kind, abstract target class, first expectation kind,
verification reason and closed proposal rejection reason. No expectation arguments, target IDs,
text, URLs, queries, hashes of website content or teacher responses are exported.
Counters describe expected/satisfied/unmet top-level checks, optional steps, at most two
observation rechecks, unreadable rechecks, verified text expansion, semantic/route changes,
and visible actionable controls for the target role before/after. An unreadable recheck or
unconfirmed deferred identity remains ambiguous; satisfied checks alone do not establish success.
The text-expansion flag identifies a directly satisfied top-level TEXT_EXPANDED check; it does not infer the winning branch of ANY_OF. An optional WAIT without expectations reports NO_EXPECTATIONS and never claims a failed item click.
PROPOSAL_REJECTED carries GIVE_UP/EMPTY/LOW_CONFIDENCE/INCOMPATIBLE/WRONG_TARGET/
HELD_PROCEDURE/DUPLICATE separately from provider transport errors. These are diagnostic
observations of existing policy; they do not grant success, lift holds or trigger extra requests.
City separately protects its first rejection, first provider error and first item outcome within the existing six-row trace. Dropped event
counts remain explicit. Existing legacy reports,1MiB trimming, queues and retention are unchanged.
Deploy independently validating receiver/publisher code and the pinned native validator before
promoting an app that emits these optional fields. New physical-phone evidence is required to
explain actual expansion failures; a diagnostics improvement is not measured marketplace learning.

### Synthetic server startup evidence

Debug serverlab maintenance keeps the existing ANR classification object unchanged (`status`, `main_state`, `observed_frames`). A separate optional `<diagnostic UUID>.anr-collection.json` sidecar records only correlated cycle/source/version, the exact selected event timestamp, event-match flag, and closed collection/cleanup enums. It distinguishes a null stream, opening/read failure, byte limit, parsed result, deadline/cancellation and worker failure. Cleanup errors never erase a completed parse or promote malformed evidence. `PARSED` means parsing ran; classification may still be `UNKNOWN`. `DEFERRED` means cleanup completion was not observed before the deadline. Raw traces, exception messages and private paths are never exported. Sidecar failure cannot remove the classification.

The debug thread sampler starts in instrumentation `onCreate`, before instrumentation-thread registration at `onStart`. Earlier samples explicitly report `NOT_REGISTERED`, rather than pretending that the main thread was the instrumentation thread. Main-thread setup constructs a path from `applicationInfo.dataDir` without calling `getFilesDir` or performing disk writes. The background worker samples the current atomic stage and stops after at most 60 samples even if `onStart` never arrives. Android installs content providers before instrumentation `onCreate`; this sampler does not cover that earlier stage. Missing/stale samples are unavailable evidence, not successful startup. These synthetic maintenance records do not establish phone-site learning.
