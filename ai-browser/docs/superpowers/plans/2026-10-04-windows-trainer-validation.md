# Windows trainer 1.0.0 validation

Implemented the lab renderer, isolated HTML practice market, production browser
perception, independent hidden-truth scoring, training-only persistent memory,
four-worker launcher, local dashboard, portable setup, stop, and results export.

Validation completed locally:
- Kotlin core suite: 240 tests, zero failures.
- Node real-browser/market/runner suite: 13 tests, zero failures.
- Renderer bridge response identity and hung-child deadline tests use real child
  processes; persistence tests reject damaged checkpoints.
- Final independent review completed. Its important finding was fixed: training
  and post-training evaluation must both have zero unsafe actions, false verified
  claims, and hard violations before checkpoint replacement. New regression
  test observed RED (missing safety gate), then GREEN in the full core suite.
- Startup lock cleanup regression observed RED, then GREEN with the Node suite.
- Android production source, signing, and version were not changed.

Windows workflow passed:
https://github.com/lukeypue/AI-Browser/actions/runs/37233752587

Verified the portable Windows PowerShell setup in a path with spaces, official
runtime downloads and checksums, actual Chromium observation/actions, four
workers saving two verified searches each, compiled skill checkpoints, restart
retaining progress, two held-out cases reusing a learned procedure, duplicate
launch rejection, rendered dashboard, both dashboard and Control.ps1 stop,
process exit, intact saved memory, and results export with build identity.

The delivered ZIP is the exact successful Windows workflow artifact, with all
21 files checked against its manifest. Tested source commit:
3ef94793ed232472846dede906a72d22b486558d.

Initial publication was blocked by automatic approval review. The user then
explicitly approved the trainer branch push. Publication and cloud tests were
completed within that authorization.

Local real-browser verification used a separately installed Chromium after
the initial browser CDN download failed. This reproduced the test's incorrect
type-and-submit assumption: the existing browser helper clicks the search
input itself when it matches its broad search-label selector. The trainer's
existing teacher uses explicit TYPE followed by CLICK SUBMIT. Tests now verify
that typing earns no submitted-search credit and the explicit click does.
Production Android perception was preserved.

The benchmark also exposed a remaining brain quality gap: a known nonmatching
axle can remain UNKNOWN. This is now counted as a rare-classification error and
fails full-task efficacy. It is distinct from hard price/mileage violations or
false verified claims. Candidate search learning remains isolated for review.

One Linux CI core run timed out on the Node test child's two-second handshake;
the rerun passed the core tests. The bounded hung-child test was preserved.

Ruling: isolated evaluation may fail efficacy without erasing safe candidate
training; evaluation writes never enter its checkpoint. Safety failures reject
replacement. Cost if wrong: weak synthetic candidates remain in lab memory,
requiring review; no candidate is automatically imported into Android.

The review's source-identity minor was resolved: Control.ps1 includes the bundle
manifest in the results ZIP, and Windows verification checks its presence.
