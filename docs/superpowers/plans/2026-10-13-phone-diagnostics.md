# Consent-based phone diagnostics implementation

The authorized origin is https://reports.ryzod.com on Lukes-Projects. DNS A reports points to 165.227.50.222. Main website and email DNS stay untouched.

1. Independently validate a strict content-free phone report schema at a loopback receiver behind normally validated TLS. Test schema injection, durable receipts, one-use pairing, conflicts, duplicate retries, six new reports/device/hour, and 30-day retention.
2. Add typed Kotlin export and serial snapshots. Test unknown-event/text rejection, canonical sites/enums, monotonic time, trim/clock/interruption flags, and flush ordering.
3. Add explicit opt-in, single-use pairing, Keystore-encrypted upload-only credential, bounded private queue and durable WorkManager retries. Disable/opt-out cancels and clears pending uploads. Test queue age/size/count bounds and HTTP contract.
4. Extend the existing publisher under its shared writer lock and expected-head pushes. Preserve synthetic files and every unreviewed phone report; independently validate every upload. Add phone-reports/index.json and immutable UUID report files.
5. Verify DNS before TLS deployment, run all suites and Android build/lint, independently review, and use the existing signed pipeline only after green verification. Human installation and pairing must precede any claim of real phone delivery or review automation extension.

Sharing starts OFF. Reports are <=1 MiB; queue <=10 reports/10 MiB; retries <=7 days. Receiver retention is 30 days; private Git history may retain sanitized copies longer. No GitHub credential is placed on the phone. No existing secrets or signing configuration are accessed or changed. No foundation-model reviewer is claimed without a successful authorized request.
