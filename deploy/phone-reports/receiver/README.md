# Phone diagnostic receiver and publisher

The authorized production origin is https://reports.ryzod.com. Do not enable the receiver or install the app until DNS, publicly trusted TLS, tests, and independent review are verified. The loopback receiver accepts only requests forwarded as HTTPS; it exposes no report download, remote-control, or Git operations.

Consent starts OFF. Pairing codes expire in ten minutes and are consumed once. Devices generate a random upload-only credential, encrypt it with Android Keystore, and send it only to this HTTPS origin. The receiver retains only the credential hash. No repository key is copied to a phone or exposed to the receiver.

Reports are strictly allowlisted: event codes, site IDs, enums, bounded counters, version/revision, timestamps, and trim/clock/interruption flags. A report is at most 1 MiB; phone pending storage is at most ten reports / 10 MiB and retries expire after seven days. Opt-out cancels jobs and clears unsent copies. Data already received cannot be recalled.

The receiver commits SQLite synchronously and fsyncs outbox files before acknowledgment. Identical ID/hash retries receive the original receipt; conflicting IDs return 409. At most six new reports are accepted per device in a rolling hour. Receipt metadata survives retry after a crash. Content and orphan files expire after thirty days; private Git history may keep sanitized copies longer.

The existing synthetic uploader becomes the sole repository writer, retaining its exclusive upload.lock. It independently validates the receiver outbox before adding immutable phone-reports/<UUID>.json documents and phone-reports/index.json. Index entries stay unreviewed until an authorized review explicitly marks them; unreviewed files are never deleted from Git. Synthetic reports remain under reports/. An ancestor check, expected-head comparison and normal fast-forward push protect updates from overwriting concurrent review changes. The receiver cannot access the uploader key or checkout.

Testing: python3 -m unittest discover in this directory, the complete standalone Kotlin core suite, Android app tests/build/lint, WebExtension Node and Playwright harnesses, Practice City runner/export tests, and independent review. HTTP/loopback fixtures are server tests, not real phone evidence. No review automation is extended until a consented, paired phone delivers a report whose ID/hash and connector retrieval are verified.

## Live progress

A separate opt-in setting sends sanitized progress about every 30 seconds during active timed phone tests. It defaults OFF and retains the existing final-report pairing. The app coalesces pending live progress to the newest bounded packet; final reports retain their durable seven-day queue. Live opt-out disconnects live delivery and clears pending progress; main sharing opt-out cancels both.

The receiver accepts at most 32 KiB and 32 allowlisted recent events per snapshot. It acknowledges only durable storage, rejects changed retry hashes and older sequences, limits 240 new snapshots/device/hour with a 25 second minimum interval, and allows one immediate terminal transition. Live snapshots expire after 30 minutes; matching final report receipt clears the snapshot. Completed reports retain the original six/hour and 30 day limits. Clock/trim/interruption flags accompany monotonic progress; trimmed counters may be partial.

The existing locked publisher independently validates live data and writes private `live-progress/latest.json` at most every two minutes for unchanged active tests, with immediate test/state changes. Typed AI advice appears in `live-progress/triage.json`. Existing synthetic files and all unreviewed phone reports are preserved. These mutable views expire, but private Git history may retain sanitized copies longer.

The prepared (disabled) advisory worker uses the server's authorized Codex CLI with tools disabled. Only bounded typed failure aggregates reach the model. It reserves attempts before transport, limits six/hour and 24/day, suppresses human boundaries, and never executes suggestions. The migration leaves its trigger disabled while the existing review automation is identified; it cannot install an app. See LIVE_TRIAGE.md. Automatic code updates still require reproduction, independent review and all release gates; a successful advisory is not proof of a fix.

Production migration uses `upgrade-live.py`, preserving the deployed city's expanded exam and clinic validators instead of replacing the exporter wholesale. A real consented phone snapshot, receipt hash and private connector retrieval remain required to establish end-to-end live operation.
