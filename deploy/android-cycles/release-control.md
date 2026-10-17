# Shared verified stable release guard

This helper is opt-in. No scheduler, service, cloud-review prompt, source push,
release, or phone installation is activated by adding these files. The existing
GitHub workflow owns version allocation and signing. A trusted operator invokes
the helper only after reviewing a specific clean candidate worktree.

All cooperating release callers on this host must use
`scripts/verified-stable-release.py`; its fixed lock is
`/var/lib/aibrowser-release-control/release.lock`. It holds the lock from final
checks through the stable source push, exact-source successful **Build AppGate APK**
push run, downloaded metadata/APK verification, and durable completion ledger.
Busy callers fail immediately. The state directory must belong to the invoking
operator and must not be writable by other users. Do not delete or replace the
lock file while a caller is running. Root is the intended deployment operator.

Review attestation (an explicit trusted operator assertion, not a cryptographic
proof of reviewer independence) must name the full candidate SHA:

```json
{"source_sha":"FULL_40_CHARACTER_SHA","status":"approved","independent":true,
 "reviewer":"reviewer identity","evidence":"review task URL or ID"}
```

Run from a trusted checkout after placing that JSON outside the clean candidate
worktree. Replace every placeholder with reviewed values:

```sh
python3 scripts/verified-stable-release.py \
  --candidate FULL_CANDIDATE_SHA \
  --expected-stable-head FULL_OBSERVED_STABLE_SHA \
  --worktree /absolute/path/to/clean/reviewed/worktree \
  --review-attestation /absolute/path/to/review.json
```

The host needs the existing Java/Gradle/Android/Node/Playwright tooling under
`/opt/ai-browser-dev` and the existing source-upload SSH identity and known-hosts
file. The helper never reads those files; Git/SSH uses them internally. Build
subprocesses receive an allowlisted environment without inherited signing or
API secrets. Fixed checks cover core and Android unit tests, debug assembly and
lint, scripts and receiver Python suites, browser perception/isolation harnesses,
and `scripts/tests/android-fixtures.test.mjs` with test concurrency set to one.
No model/review text becomes a command. Android checks use no daemon,
one worker, 768 MiB heap, and in-process Kotlin compilation.

The controller verifies a normal fast-forward ancestry and checks the expected
stable head before checks and immediately before push. Git also rejects any
non-fast-forward push. Its local lock coordinates this host's cooperating
callers; an unrelated remote writer does not honor this lock. The existing
workflow concurrency and publish-head guard remain in place. No force push is
used. The latest stable metadata must match the candidate and workflow version
allocation, and the downloaded APK must match that metadata, package
`com.aibrowser.universal`, and stable certificate SHA-256
`118079b156fabeae30aca7ad845f1a15cd7eea89740bc1472be2163400d40239`.

`/var/lib/aibrowser-release-control/ledger.json` records only verified completion,
with file and directory fsync plus atomic replacement. Failures before atomic
replacement preserve the previous record. If replacement succeeds but the
following directory fsync fails, a new fully verified record may already be
visible while its persistence across a crash is unconfirmed; the helper reports
failure. Inspect the visible ledger and exact workflow/artifact evidence before
recovery, and confirm storage health and durable ledger persistence. Do not
assume either rollback or durable completion from that error. A failure after
push cannot undo that push or an already
published workflow release. Investigate the exact SHA and run before deciding
on recovery; the helper deliberately does not automatically resume, reset,
republish, or claim a failed verification completed. Later releases must advance
both verified version 918 and the previous completion ledger.

Fake-only verification, without release actions:

```sh
python3 -m unittest discover -s scripts/tests -p test_release_control.py
```

To integrate an existing cloud-review task, first identify its actual task ID
and update its trusted release instructions to call this helper with a candidate
and independent review evidence. Until that is done, this guard is available
but global release orchestration is not established.
