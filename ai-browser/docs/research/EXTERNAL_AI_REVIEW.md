# External AI review packet

Prepared 2026-09-29. **No Claude or Gemini service was contacted and neither has reviewed this implementation.** These are ready-to-run prompts for the owner or a separately authorized review. Do not represent internal coding workers as independent provider evaluations.

## Supply this material

Use a sanitized source archive or repository checkout containing:

- `AGENTS.md` and `docs/BRAIN_ARCHITECTURE.md`.
- `docs/superpowers/specs/2026-09-29-local-first-brain-design.md` and `docs/superpowers/plans/2026-09-29-local-first-brain.md`.
- `docs/research/2026-09-29-brain-research.md`.
- The complete `brain-core` implementation/tests, Android service/settings changes and bundled `sitebrain` extension.
- The final baseline/updated `local-first.json`, whole-suite JUnit/extractor/build results and patch diff when available. Identify the exact commit/worktree snapshot and commands used.

Exclude signing material, API keys, cookie/session storage, personal browsing exports, raw page content and user queries. Aggregated diagnostic counts in the report suffice. Missing evidence should be named explicitly rather than invented.

## Shared review instructions

Review code and test behavior independently. The goal is reusable, verified navigation with fewer unnecessary model requests—not to promise universal autonomy or rank providers without testing.

Start with these contracts:

1. Typed verifier outcomes alone grant success or curriculum credit. Unavailable targets remain partial/deferred; DONE cannot substitute for the requested outcome.
2. One-minute observations continue without creating endless generic practice tasks. Repeated failure is bounded, but changed capabilities can become eligible again.
3. Transferred procedures preserve parameters, current grounding and per-host failure history. A target-host failure cannot be erased by more source successes. Reject account/composer/commit procedures.
4. Re-observation must replace stale IDs and effects. A newly dangerous replacement cannot inherit a harmless classification from an old action.
5. All actual teacher HTTP attempts reserve the same durable allowance per installation: 24 per rolling day, 6 per rolling hour. Failed requests count; restarting, clock rollback, corrupt storage, evidence routes and provider errors cannot bypass it. This is neither a dollar cap nor a global user/device quota; developer-funded fleet billing requires server-side quotas. Local-only makes zero outbound teacher requests and costs zero in API usage, without guaranteeing website success or training foundation-model weights.
6. No silent model/provider escalation. Reported reasoning/cached tokens are subsets where appropriate; missing usage is not a measured zero bill.
7. Auth/CAPTCHA/2FA/paywall and grant/network boundaries remain intact. No secrets or personal content enter logs, error messages, exported skills or cross-project memory.
8. Synthetic host/control variation is not a production-site generalization benchmark. A supplied source program is not autonomous discovery, and a builtin success is not learned transfer.

For each finding provide:

- Severity and concise title.
- Exact file and current line range, with a short code excerpt if useful.
- Triggering inputs/state, reachable execution path, expected versus actual outcome.
- A minimal reproducible test or command; distinguish executed evidence from inference.
- The smallest justified correction, its regression risk and an acceptance test.

Do not claim “tests pass” unless you ran them. Do not call a hypothetical concern a confirmed bug. Group duplicates, prioritize consequential defects, and state when no actionable issue was found in a reviewed area. Cite official current sources for provider/runtime recommendations and date them. Avoid generic praise, empty promises, unsupported speedups and recommendations based only on model size.

## Prompt for Claude: implementation and adversarial review

> Act as an independent Kotlin/Android code reviewer. Read the supplied contract and diff, then trace the real engine, memory, transport and service paths. Concentrate on false verification, stale-target/effect reuse, unsafe transfer, request-budget bypass, restart/concurrency/clock behavior, privacy leaks and compatibility regressions. Read tests skeptically: identify fixtures that seed success, use the same implementation to compute expected values, or mistake a builtin action for transferred learning. Run available tests locally without contacting APIs or reading secrets. Produce prioritized findings in the required evidence format, plus a short coverage table marking inspected, executed and untested areas. Separate release-blocking defects from optional improvements. If a proposed change would weaken human-only or grant boundaries, reject that change and explain a safer approach. Do not modify production files unless the owner explicitly asks you to implement findings.

## Prompt for Gemini: challenge the architecture and evaluation

> Act as an independent browser-agent researcher and systems reviewer. Use the source, diagnostics aggregates, design and evaluation artifacts supplied. Challenge whether this implementation truly reduces unnecessary work and supports reusable verified procedures. Examine absent pagination, unseen facet choices, changed mobile layouts, multi-page prerequisites, target-host failure suppression and partial-task semantics. Design a small held-out suite that varies page structure—not merely hostnames—and compare builtin policy, same-host replay, transferred procedures, local recovery and optional teacher repair under equal task budgets. Specify outcome oracles, negative controls, action/request/token measurements and acceptance thresholds before suggesting experiments. Review current official model/runtime documentation, but do not make paid requests or claim comparative provider quality from token prices. Evaluate whether an on-device adapter is justified; require phone RAM, battery, thermal and verified-outcome evidence, and distinguish procedural memory from weight training. Return concrete findings with files/lines/reproductions, followed by at most five experiments ranked by decision value. Label hypotheses and unexecuted tests clearly.

## Review handoff

The owner can provide both responses to the implementation team. Reproduce material findings before changing code; resolve disagreements using the contract and executable evidence. A review response is not approval to buy services, export browsing data, remove safety boundaries or publish a release. Record reviewer model/version, review date, inspected commit, tools actually run and remaining uncertainty when a real external review occurs.
