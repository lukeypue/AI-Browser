# Phone diagnostic receiver and publisher

The authorized production origin is https://reports.ryzod.com. Do not enable the receiver or install the app until DNS, publicly trusted TLS, tests, and independent review are verified. The loopback receiver accepts only requests forwarded as HTTPS; it exposes no report download, remote-control, or Git operations.

Consent starts OFF. Pairing codes expire in ten minutes and are consumed once. Devices generate a random upload-only credential, encrypt it with Android Keystore, and send it only to this HTTPS origin. The receiver retains only the credential hash. No repository key is copied to a phone or exposed to the receiver.

Reports are strictly allowlisted: event codes, site IDs, enums, bounded counters, version/revision, timestamps, and trim/clock/interruption flags. A report is at most 1 MiB; phone pending storage is at most ten reports / 10 MiB and retries expire after seven days. Opt-out cancels jobs and clears unsent copies. Data already received cannot be recalled.

The receiver commits SQLite synchronously and fsyncs outbox files before acknowledgment. Identical ID/hash retries receive the original receipt; conflicting IDs return 409. At most six new reports are accepted per device in a rolling hour. Receipt metadata survives retry after a crash. Content and orphan files expire after thirty days; private Git history may keep sanitized copies longer.

The existing synthetic uploader becomes the sole repository writer, retaining its exclusive upload.lock. It independently validates the receiver outbox before adding immutable phone-reports/<UUID>.json documents and phone-reports/index.json. Index entries stay unreviewed until an authorized review explicitly marks them; unreviewed files are never deleted from Git. Synthetic reports remain under reports/. An ancestor check and an exact expected-head lease protect fast-forward pushes from overwriting concurrent review changes. The receiver cannot access the uploader key or checkout.

Testing: python3 -m unittest discover in this directory, the complete standalone Kotlin core suite, Android app tests/build/lint, WebExtension Node and Playwright harnesses, Practice City runner/export tests, and independent review. HTTP/loopback fixtures are server tests, not real phone evidence. No review automation is extended until a consented, paired phone delivers a report whose ID/hash and connector retrieval are verified.
