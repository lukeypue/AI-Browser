# Planner failure diagnostics implementation plan

Goal: make the next app and City report explain typed AI repair failures without exporting secrets or arbitrary error text. Evidence: all31errors in build856 phone test df1d1fc8 are HTTP401; automatic reports currently omit the reason. This does not prove provider or credential cause.
Architecture: shared closed failure classification, optional schema2 fields and whole-test counters, monotonic request duration, first-error retention in bounded City traces, and a separate synthetic diagnostic clinic. Preserve existing learning, verifier, pacing, queue, signing, services, exams and single review automation. Unknown metadata becomes UNKNOWN; no models/endpoints/keys/body text exported.

- [ ] Reproduce missing HTTP401 metadata and trim-resistant failure totals with actual engine/exporter tests; independent validator tests fail before implementation.
- [ ] Add typed failure classification and enum-only provider identity, bounded HTTPstatus and monotonic duration; retain first error in City samples. Do not infer credential cause or add network retries.
- [ ] Stream fixed-category whole-test counters before trimming; export optional totals and show auth warning in final phone summary. Backward-compatible strict independent validation precedes app rollout.
- [ ] Add unscored City engine controls for401/403/429/503/timeout/invalid-response/success and bounded traces; fake transport only. Validate and publish the optional block independently.
- [ ] Run core/app, receiver/publisher/runner/browser, Android build/lint gates and independent review. Obtain actual Claude consultation before release; currently signed out. No publication while that gate is unavailable.
- [ ] After all gates, use existing serialized stable signed release and guarded server promotion; verify actual new reports and preserve running timers. Physical-phone delivery and connection repair need real evidence, not synthetic claims.

Review focus: malicious exception text/config cannot leak; old schema1/2 and historical City reports remain valid; counters freeze at deadline; first error retained despite many events; strict out-of-range and legacy-injection rejection. HTTP401 means selected connection rejected authentication, not proof of expired key. Server Claude login is separate.

## Execution notes
- Clean baseline core suite passed; remote stable head396543 matched the dedicated worktree.
- Four missing diagnostic regressions failed by assertion, plus deadline aggregation and identical callback retention; typed metadata and initial focused provider tests then passed. Independent Python valid-new-schema/false-control regressions failed before validation changes, then passed.
- Independent review found a protected-event displacement bug; both orderings now have a regression pending RED/GREEN.
- The new City integration test initially used experiment(0), but the API requires10..10000. Its initial failure was invalid test input, not valid proof of missing integration. Corrected to minimum10; a proper absent-wiring regression and final integration run are required before completion.
- Receiver/publisher123tests and runner110tests passed before the final review correction. No release or runtime deployment has occurred. Actual Claude auth status is logged out; required external consultation remains blocked.

- Proper integration RED subsequently passed its minimum input and failed the intended missing-clinic assertion with production wiring temporarily absent/restored. Both pin-order review regressions failed before the correction. Final integration assertion and wire fixture now reuse the existing PracticeCityTest experiment to avoid an extra full expensive exam run.
- Review correction protects both first-error/opening ordinals and the last-three rows before budgeting remaining first rows. Identical callback bodies remain distinct. The minor missing-terminal/unexpected-call finding was promoted to required diagnostic robustness: Python RED/GREEN now preserves those controls as explicit false findings (missing result is null); no error body is accepted.

- Final focused core/City diagnostic and protected-event regression suite passed after review fixes; receiver/publisher124tests and runner110passed. A local checkpoint commit is required to bind clean-source browser fixture gates. Full Android/core/app/browser gates remain pending; this checkpoint is not a release approval. No signing/workflow/version changes.
