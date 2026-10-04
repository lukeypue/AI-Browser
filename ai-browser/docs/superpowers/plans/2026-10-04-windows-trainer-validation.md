# Windows trainer validation and remaining gate

Implemented the lab renderer, isolated HTML practice market, production browser
perception, independent hidden-truth scoring, training-only persistent memory,
four-worker launcher, local dashboard, portable setup, stop, and results export.

Validation completed locally:
- Kotlin core suite: 239 tests, zero failures.
- Node market/runner suite: 9 tests, zero failures.
- Renderer bridge response identity and hung-child deadline tests use real child
  processes; persistence tests reject damaged checkpoints.
- Final independent review completed. Its important finding was fixed: training
  and post-training evaluation must both have zero unsafe actions, false verified
  claims, and hard violations before checkpoint replacement. New regression
  test observed RED (missing safety gate), then GREEN in the full core suite.
- Startup lock cleanup regression observed RED, then GREEN with the Node suite.
- Android production source, signing, and version were not changed.

Remaining gate: actual Chromium tests, Windows portable setup in a path with
spaces, four-worker practice, saved-learning inspection, restart, and export.
The Windows Actions workflow is ready. Automatic approval review rejected git
push because the task authorized building, but did not explicitly authorize
repository publication. Do not bypass this through the GitHub API. Local
Chromium download also failed (invalid/truncated archive), so it provides no
browser-test evidence. This ZIP is a preview, not a Windows-verified release.

Ruling: isolated evaluation may fail efficacy without erasing safe candidate
training; evaluation writes never enter its checkpoint. Safety failures reject
replacement. Cost if wrong: weak synthetic candidates remain in lab memory,
requiring review; no candidate is automatically imported into Android.

Deferred minor from review: exported reports omit source identity. Keep the
original trainer ZIP, whose manifest contains the source commit and file hashes,
alongside results until build identity is included in the export.
