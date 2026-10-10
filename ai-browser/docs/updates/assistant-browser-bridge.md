# Assistant + AI Browser integration design

Requested October 10, 2026. This is a design for a future implementation; version 7.2.12 does not connect ChatGPT to the phone or provision a cloud server.

## Intended behavior

A request such as “Find Toyota Siennas under $10,000 within 100 miles” should combine public web discovery with the user's signed-in browser. Resolve the search center from a user-selected location. Search relevant sources, collect and deduplicate listings, verify available price/location evidence, and return links with source coverage and any uncertainty. “All listings” must not be claimed when sites or pagination were not covered.

When a reusable procedure fails, explain the current task, action, pass condition, and failure. The model proposes a bounded repair; the browser executes it and verifies the result. The person can demonstrate a focused procedure when needed. Save a procedure after verified replay and retrieve it on subsequent tasks with new values.

## Required components

| Component | Responsibility |
| --- | --- |
| Phone browser | Keep the live signed-in GeckoView session, observe controls, execute typed actions, verify outcomes, and store private site memory |
| Task bridge | Pair an authorized assistant with one device, enqueue tasks, expose task-local status and results, and deliver cancellation and human handoffs |
| Reasoning server | Host a selected open-weight model or route to an optional remote teacher; propose bounded typed programs |
| Learning service | Train a task-specific selector or adapter on deliberately collected, redacted, verified examples; evaluate changes before deployment |
| Assistant tools | Discover public sources, create a browser task, read progress/results, propose a repair, cancel, and request a human handoff |

## Bridge interface

Prefer an authenticated MCP service or equivalent purpose-built connector. Proposed operations: `create_search_task`, `get_task_status`, `get_task_results`, `get_task_observation`, `propose_task_steps`, and `cancel_task`. These operations are not installed tools today. Pairing is explicit and revocable; task IDs and device IDs are scoped to the paired user. The phone makes an outbound connection so no public inbound port on the handset is required.

Requests accept structured task goals and typed browser actions, never arbitrary executable code. The phone validates every proposal using the existing executor, site boundary, and verifier. Action ordering, budgets, duplicate request handling, cancellation, and reconnection must be tested before this becomes a remotely operated browser.

Authentication stays in the user's browser. Session cookies and passwords are not exported to the reasoning server. Valid sessions are reused; expired sessions, login walls, and new challenges cause a visible human handoff. No permanent access after one verification is promised. Sending, purchasing, posting, and other external changes retain their existing explicit grants.

## Performance and proof

First measure a single site: time to first usable result, verified task success, repeated-task action count, model calls, retained procedure reuse with a different value, and cost. Broaden source coverage only after these pass. Separate model latency from page loading, verification, and retry delays. GPU deployment is justified by measured gains rather than by server availability alone.

Milestones: (1) repair and explain local procedural learning, delivered in 7.2.12; (2) paired task bridge plus a read-only search pilot; (3) compare an owned model server with the existing teacher using the same tests; (4) adapt a model or selector from verified examples and test held-out tasks. This is a controlled learning-agent project; a general foundation model trained from scratch is a separate undertaking.
