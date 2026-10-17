# Continuous Android Cycles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task.

**Goal:** Verified continuous30-minute actual Android app tests retaining learning, reports/recovery and the existing review/release loop.
**Architecture:** Isolated official emulator and serverlab debug test seam exercise actual app runtime on HTTPS fixtures. Persistent host controller finalizes restart-verified reports through existing publisher; existing review receives a new index.
**Tech Stack:** Kotlin/Android Instrumentation, GeckoView, Python unittest/systemd/flock, HTTPS static fixtures and existing Git publisher.
**Spec:** ../specs/2026-10-17-continuous-android-cycles.md

## Global Constraints
No secrets/signing/workflow edits or phone access. Preserve existing dirty guidance, City/uploader/review. Runtime and content labels exact. Complete means >=1800000ms real monotonic time plus restartproof. No new review automation, no forcepush, no needless APK release.

## Review Focus
- Crash after successful app work but before durable host state: stable UUID/reconcile/dedup.
- New process/emulator restart: retain projection; no pm clear/uninstall/wipe-data.
- Malicious/oversize reports: independent closed schema and bounded counters/hash checks.
- Deadline/flush races: actual service end, writer barrier, engine quiescence; no stale callbacks counted.
- Parallel source/release workers: common lock, expected sourcehead, exact-SHA artifact verify.

### Task1 Android runtime seam and instrumentation
Files app/src/{debug,release}/java/com/appgate/tv/serverlab/ServerLabRuntime.kt; existing BrainService.kt/DiagnosticsLog.kt; app/build.gradle minimal debug/test config; app/src/androidTest/.../ServerLabInstrumentation.kt; app/src/test/.../ServerLabRuntimeTest.kt.
- [ ] Write failing validation/default-isolation/observer privacy tests, observe RED, implement minimal variant seam and GREEN.
- [ ] Compile actual debug/test APK; smoke actual engine/Gecko/HTTPS fixture; no live-site actions.
- [ ] Prove typed output and memoryprojection after engine barrier with no rawdata.

### Task2 HTTPS fixtures
Files scripts/android-lab/fixtures/{shared.js,classifieds/index.html,cars/index.html,marketplace/index.html,offerup/index.html,manifest.json}; scripts/tests/android-fixtures.test.mjs.
- [ ] Test-first search/filter/pagination/detail physical effects and controlled challenges; no arbitrary network or mutations.
- [ ] Serve only new /practice-fixtures/ HTTPS path on authorized reports.ryzod.com; normalTLS; preserve receiver/site/email.

### Task3 Controller and independent report validator
Files scripts/android-cycle.py; scripts/android_cycles/{controller.py,transport.py}; deploy/phone-reports/receiver/android_cycles.py; scripts/tests/test_android_cycles.py; receiver/test_android_cycles.py.
Interfaces local instrumentation output and host wire schema exactly as spec.
- [ ] RED for source/status/duration/clock/boolean/counter/extra-field/hash/oversize/restart mismatches, crash/lock/dedup and recovery.
- [ ] Implement fixedserial trusted argv, durable journal/outbox, quiescent capture and two restart probes.
- [ ] GREEN; interrupted recovery never becomes completed.

### Task4 Existing publisher/review and release gate
Files existing uploader source tracked in deploy/phone-reports/receiver/synthetic_export.py and installed /opt/.../export_reports.py (identify canonicalfile first); validator/shared destination allowlist; tests; scripts/release-guard.py and tests.
- [ ] RED for arbitrarypaths, conflicts, index/unreviewed preservation, competing writer, symlinks and duplicate UUID/hash.
- [ ] Add prepare_android_cycles to existing writer, no new credentials/writer/review automation; verify one report connector retrieval.
- [ ] Configure existing review only once identified through supported control; test actual AI request independently.
- [ ] Serialize final checks/push/CI/artifact verification with commonrelease flock.

### Task5 Verification and operation
- [ ] Full core/app tests, Python receiver/publisher/runner/controller, browser fixture harnesses, Android build/lint and independent review.
- [ ] Commit clean sourcepins before actual acceptance cycles; deploy immutable code/APKs without signing changes.
- [ ] Complete first actual30-minute cycle, app restart probe, emulator restart probe; validate/deliver/hash/retrieve report.
- [ ] Run interrupted recovery check and second full30-minute cycle with same appdata, restartproof and retained/new learning separately.
- [ ] Enable continuouscycle systemd only after acceptance; preserve originaltimer/uploader hashes.
- [ ] Publish only reproduced focused production fixes through stable signed pipeline and commonlock; verified server-only tests need noAPK release.
