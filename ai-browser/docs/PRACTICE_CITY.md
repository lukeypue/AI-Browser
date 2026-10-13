# Site Brain practice city

The practice city expands the existing offline Kotlin simulation. It uses the real
brain, semantic parser and verifier against synthetic marketplace observations.
It is not a browser rendering real websites and does not train foundation-model weights.

## What expanded

- Eight practice districts and eight separate exam districts.
- Catalogs of 32, 64, 96 or 128 synthetic listings, shuffled reproducibly.
- Four vehicle queries; one to four cards per page; next-page and cumulative load-more layouts.
- Nine existing difficulty levels, inline/drawer filters, missing evidence, dialogs,
  stale controls and delayed loading.
- A new 144-case fixed exam before and after training. The original 24-case exam
  and separate 24-case transfer exam remain intact for comparison.
- A normal scheduled session trains for 3,600 episodes, then scores 384 exam episodes.
  Set `-PbrainTrainingEpisodes=1200` (or another value in 10..10000) for a smaller run.

Training seeds are negative; exam seeds are positive. Exam hosts never appear in
training. Every exam case forks the frozen memory snapshot. Correct answers live only
in the environment and scorer. Reports retain source revision/hash and case manifests.
An engine task's success label is not accepted as ground truth.

## Automation and its limits

`Site Brain Practice City` is a dedicated GitHub workflow: it runs on relevant code
changes and offers an hourly schedule after the workflow exists on the repository's
**default branch**. It verifies the core and runner first, then performs one bounded
session. Concurrency prevents two sessions on the same ref from running together.
It has read-only repository permission and publishes only a synthetic report artifact
(retained for two days) and aggregate metrics in its log. It never releases an APK.

The existing AI Browser review can inspect these actual reports and failures. It cannot
read local Droplet reports unless an authorized upload channel is separately configured.
The server timer below is an alternative runner; disable the GitHub schedule if moving
all scheduled sessions to the Droplet, to avoid unnecessary duplicate computation.

Each session starts with fresh synthetic memory. Procedural learning is shared within
that session, and before/after exams measure transfer. Sessions do **not** accumulate
permanent knowledge on a phone. No synthetic memory is automatically imported into
Android; no real phone test, personal logs, external model call or paid API use occurs.
A high synthetic score is not evidence of mastery of Facebook, KSL or OfferUp.

## Manual run

From the repository root, prepare dependencies once and run:

```bash
gradle -p ai-browser/brain-core test --no-daemon
python3 scripts/practice-city.py --project "$PWD" --output-dir /tmp/aibrowser-city-reports
```

The runner takes an exclusive lock, imposes a 30-minute total process deadline,
checks source provenance, requested episode count, exam completeness and zero
unsafe actions/false verification claims/hard-constraint violations. It atomically
archives the report and retains at most 48 runs. A failed experiment is not promoted
or published as an app update. Inspect success rate and recall as well as safety.

## Optional DigitalOcean installation

Use the existing **Lukes-Projects** SSH connection. From a verified clean checkout of
this source, run `bash scripts/install-practice-city.sh` as root. The installer creates
an unprivileged `aibrowserlab` account and a separate sparse source checkout under
`/var/lib/aibrowser-practice-city/source`. It does not modify the original workspace,
signing configuration, or existing release workflows. It rejects an active session,
a modified checkout or a different installed revision rather than replacing them.

It warms dependencies, runs the tests, and requires a valid first 3,600-episode report
before enabling `aibrowser-practice-city.timer`. The installed service has no external
network, a 3 GB memory limit, two CPU cores of quota, and a 35-minute service deadline.
It repeats one hour after the previous run ends and survives server/laptop restarts.
Source upgrades are explicit; it never pulls or edits source on a timer.

Check activation with:

```bash
systemctl list-timers aibrowser-practice-city.timer --no-pager
systemctl status aibrowser-practice-city.service --no-pager
```

Reports are at `/var/lib/aibrowser-practice-city/reports/latest.json`.
Stop scheduled sessions with `systemctl disable --now aibrowser-practice-city.timer`.
A running experiment is separately stopped with `systemctl stop aibrowser-practice-city.service`.
Only observed service state and an actual report establish that server training started.
