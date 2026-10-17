# Actual Android cycle lab

This is Android/Gecko execution against controlled synthetic HTTPS pages, not the JVM City and not phone/live-site evidence. Serverlab is an isolated debug application ID with native x86 libraries; release remains ARM64 and keeps the existing signer.

Do not enable continuous operation until two actual completed30-minute cycles and clean app/emulator restart memory proofs have passed. In-flight crashes become INTERRUPTED. Never wipe app data, shorten time, reuse stale probes or relabel City evidence as Android.

Install immutable scripts/android-cycle.py and scripts/android_cycles under /opt/aibrowser-android-cycles, plus receiver/{android_cycles,receiver,live_progress}.py under its receiver directory. Pin exact committed source/version/APK/testAPK/fixture manifest hashes in config.json. Use only emulator-5580. Private emulator/ADB state stays root0700; only sanitized outbox is root:aibrowserupload0750 and files0640.

The existing publisher remains the sole writer under its existing shared upload.lock. Install tracked synthetic_export.py as export_reports.py only after tests/review, with its validator dependencies. Add upload-service.conf and upload-path.conf as drop-ins to EXISTING uploader units. Receipt directory /var/lib/aibrowser-android-publish/receipts is root:aibrowserupload0770. Private Git history can retain sanitized reports longer than receiver retention.

Published files: android-cycles/<UUID>.json and android-cycles/index.json. Report identities/hash conflicts are immutable. Every unreviewed report is preserved. A full1MiB index defers Android publication; bounded pending capacity then pauses new cycles. City/phone publication continues. Index sharding is not implemented yet.

All four fixtures share one host/curriculum and may reuse a previous layout. Android cycle completion proves runtime/persistence only; it does not guarantee four-layout coverage. Browser harnesses test each physical layout independently.

The existing review task must be identified through a supported control before its monitoring prompt is extended. Do not create another review automation. Existing stable workflow concurrency/head guards stay in place; final source checks, normal fast-forward push and exact-source signed artifact verification must share a release lock when integrating the existing reviewer. Do not publish an APK for server-only test deployment.
