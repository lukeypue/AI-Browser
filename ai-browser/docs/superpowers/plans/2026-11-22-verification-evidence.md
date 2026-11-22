# Verification evidence implementation plan
> Execute inline using superpowers:executing-plans; independent review before publication.
**Goal:** Explain waits, unmet outcomes and rejected teacher proposals without sharing personal data.
**Architecture:** Pure typed evidence from existing verifier result and engine observations, projected by existing phone/City allowlist; independent Python validation.
**Tech Stack:** Kotlin/JVM, Android, Python.
**Spec:** ../specs/2026-11-22-verification-evidence-design.md
## Global constraints
No reward/policy/signing/auth changes, no secrets or page content, no new automation; old schema compatibility and queue/retention limits remain.
## Review Focus
- Unknown enum/free-text injection must be dropped/rejected.
- A WAIT without expectations is not a failed item click.
- Unreadable or identity-mismatched deferred outcome never earns success.
- New app schema must be accepted by independently deployed validators first.
- Trace trimming must retain bounded teacher rejection evidence and accurate counts.
## Task1: Typed evidence and tests
- [ ] Add failing pure projection and actual-engine outcome/rejection tests; run and confirm missing evidence.
- [ ] Implement VerificationDiagnostics and engine integration using actual verifier results, bounded observations and rejection reasons; project only closed values.
- [ ] Add Python validator tests, observe RED, implement strict acceptance and privacy bounds; preserve legacy reports.
- [ ] Add City retained rejection regression RED then implement; run full suites, browser harness, Android build/lint.
- [ ] Independent whole change review, address real findings with RED/GREEN; commit.
## Task2: Verified rollout
- [ ] Coordinate existing review, preserve release lock and expected head864.
- [ ] Update independent validators before emitting release, run normal stable signed controller and verify artifact/allCI.
- [ ] Update City using existing locked path and verify sanitized report delivery.
- [ ] Qualify/pin native update through existing guard, retain memory, verify restart persistence and continuing loop. No phone installation or real-site claim.
