# Local-first Site Brain upgrade

## Intent and authorization
The owner requested deep research, independent AI ideas, implementation, testing and a ready next update without repeated approval stops. This design carries that authorization through routine engineering choices. Goal: learn unfamiliar sites efficiently, reuse knowledge across compatible sites/projects, and make paid reasoning an occasional teacher. Larger downloads are acceptable; phone memory, battery, verified outcomes and cost still matter.

## Evidence
The latest diagnostics export contains 572 tasks, 522 ledger-counted AI calls and zero new lessons over about 4h38m. The earlier export is its prefix, not another run. Compiled steps verified 141/219, planner steps 98/704; these are observational step outcomes, not a controlled model comparison. 197 pagination lessons never executed PAGE_NEXT. Existing code has typed verification, deterministic skills, persistent per-host memory, but retries unavailable lessons, restricts learned reuse to the same host, and defaults to large remote models. Actual token spending was not recorded.

Research supports reusable short programs and fresh semantic grounding: Agent Workflow Memory (https://arxiv.org/abs/2409.07429), SkillWeaver (https://arxiv.org/html/2504.07079v1), Stagehand deterministic scripts (https://docs.stagehand.dev/v3/best-practices/deterministic-agent), Playwright locators (https://playwright.dev/docs/locators). Their results do not establish our performance. WebArena Verified (https://github.com/ServiceNow/webarena-verified) motivates deterministic outcome tests and held-out cases.

## Choice
1. A bigger remote model on every miss is easy but retains recurring cost and repeated exploration.
2. Bundling a small local model could remove API bills, but needs device performance and held-out action-quality evaluation. Model download size does not imply sufficient capability.
3. **Selected:** improve the pure Kotlin procedural brain, targeted practice, portable verified skills, local recovery and bounded optional teachers. Preserve the PlannerClient adapter for later on-device evaluation.

## Components and contracts

### Targeted practice
Record content-free observed capabilities and canonical choice facet keys. Practice actual observed controls and unmet lessons. An unavailable lesson is waiting for an opportunity, never falsely verified or declared unsupported forever. Keep one-minute site rechecks; after repeated unproductive attempts, defer that lesson for a bounded interval and allow a changed control layout to make it eligible. Do not repeatedly perform a complete generic search task for an absent Next or Load more control. Choose available canonical choice facets rather than hardcoding condition=used. No model evidence extraction for synthetic training detail goals.

### Portable procedures
Compile only end-to-end verified read/navigation/local-filter procedures. Parameterize values and remove page names/URLs. Canonical body identity is independent of host; outcome statistics remain per host. Same-host successful skills rank first. A compatible procedure from another host may enter probation only with at least two verified source successes, posterior mean >=0.75, matching page/entry affordance, bound parameters, and no target-host failure. A failed transfer is not repeatedly retried. Auth, account, composer and commit procedures never transfer. Transfer success is credited only by existing typed postconditions.

### Local repair
A bounded local planner can construct a short recipe from typed, live controls before remote repair. It handles search, numeric/choice facets, open/apply filters, sorting and known navigation capabilities; it cannot invent selectors or execute arbitrary code. It rejects missing/ambiguous/unsafe controls, uses original goal values, tracks attempt shapes and runs through the same executor/verifier. A fresh page requires fresh grounding. Never reuse prior-observation alternate IDs or effect classifications.

### Teacher control and accounting
Default learning allowance: 24 actual teacher requests per rolling 24 hours, with at most 6 per rolling hour, persistent across tasks/restarts. Reserve before transport; failed calls count. Local-only mode makes zero remote requests. This is a request ceiling, not a dollar guarantee. Keep explicit model choices, remove silent fallback to larger/other models, and use a documented economical default when model is blank. Show provider/model, request counts, reported input/output token totals and budget status without keys/page text. Support explicit OpenAI Responses, Anthropic Messages and compatible Chat Completions transports (including Groq); add Gemini only with verified documented protocol/model support. HTTP errors never expose response bodies/credentials in diagnostics. No new account or paid subscription is required by the update.

### Reusability and boundaries
Keep brain-core pure Kotlin with no Android/runtime model dependency. Browser renderer and AI transport remain adapters. Cross-project reuse means reusable engine and procedure contracts; it does not mean a generally trained foundation model. No authentication/CAPTCHA/2FA/paywall bypasses, account changes or personal browsing export. Public structured data and documented APIs remain future adapters unless a concrete tested use is found.

## Acceptance
- Existing JVM safety/perception/engine tests and JS extractor fixtures pass with real test tooling.
- Absent pagination does not trigger repeated paid training; changed affordances can re-enable practice.
- Learning detail practice uses no remote evidence calls.
- Verified compatible source skill executes on held-out host without a teacher; incompatible/failed/unsafe transfer is rejected.
- A stale alternate is re-grounded with current effect and identity.
- Persistent budget survives new ledgers and reconstructed stores; local-only uses no client; provider failures cannot trigger expensive model switching.
- Usage parser/transport tests use fixtures/local fake transport; never bill real keys in automated tests.
- Publish only after review, green build, stable signing and release descriptor verification.

## Limits
Synthetic tests cannot prove success on every live website or that this product beats all competitors. On-device model integration requires separate on-phone quality, RAM, heat and battery measurements. Free API tiers have quotas and can change. Logs after installation provide the live-site check.
