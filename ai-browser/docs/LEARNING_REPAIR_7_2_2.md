# Learning repair: 7.2.2

## Why this update exists
The September 30 7.2.1 diagnostic export covers 09:34:14 of learning. It contains 44 task outcomes (24 Cars, 20 Classifieds), zero new lessons and zero inspected details. All 1,893 rechecks reported available=false and human_hold=false. Of 138 action outcomes, 55 were verified actions; these were not 55 new lessons. Classifieds repeatedly opened and dismissed the same filter panel. Cars repeatedly clicked a result without reaching a verified detail page. OfferUp and Facebook had rechecks but no learning task outcomes in this window.

The two submitted exports are byte-identical (SHA-256 58d117637a8a826d3e13b6dd6f801956fdc39475c7708f05a2353f338fd8cb40), so they are counted once. These counts are observations, not claims about every possible site failure.

## What changes
- Recognize filter drawers from their controls, including narrow drawers and accordion openers. Preserve their workspace during learning instead of dismissing it.
- Expand named facets, select the requested choice, and avoid skipping Apply just because the pending form value already matches. Verify the requested facet being revealed.
- Keep observed listing anchors in the current browser tab during TRAIN, where Gecko refuses unattended popups. Preserve normal target behavior outside TRAIN and retain stale-document, auth and network interlocks. This fixes a reproduced generic failure mode; the exact live Cars link behavior still requires a phone retest.
- Respect unfinished lesson cooldowns instead of repeatedly shortening them. Record typed unavailability reasons, page type, settle state and structural control counts without listing text, queries or option values.

## Evidence and limitations
The imported Claude regression tests reproduced the filter open/dismiss loop on 7.2.1. The GitHub browser fixture reproduced a listing opening in a refused popup: 8 of 9 tests passed before the fix, with only the new training navigation assertion failing. Existing own-source isolation tests remain passing.

No live phone session, CAPTCHA, login or account action is automated during these tests. Local Chromium launch is restricted by this execution environment; browser fixtures and Android build gates run in the existing GitHub workflow. No paid teacher requests were used.

Existing search-ledger retention and customer-search scheduling need a separate lifecycle change; this release does not claim those review findings are resolved. More AI calls and longer unattended runtime are not evidence of learning.

## Phone acceptance check
Install the signed update, then restart learning. On Classifieds, look for selecting and applying a filter without immediately dismissing the panel. On Cars, look for a verified detail page after a listing click. Export diagnostics after 20–30 minutes. Compare new_lessons, inspected and action outcome codes, and use learning_recheck.reason to explain idle sites. Never count helper actions or elapsed runtime as completed lessons.

## Handoff for other AI reviewers
Read the full source ZIP and this note, append your findings to the shared master review document, and preserve previous review sections. Cite exact files, functions and reproducible evidence. Research current official documentation on the web where platform behavior matters. Separate reproduced defects, source-supported findings, hypotheses, tests actually run and untested phone behavior. Do not infer implementation from filenames. Do not request credentials, signing material, private browsing history or unredacted session data.

## Independent review disposition
Two important findings were reproduced with failing tests: numeric family expansion was incorrectly rejected, and generic checkbox dialogs were protected as filters. Numeric RoleAppeared now recognizes only the expected canonical numeric family; explicit min/max expectations stay exact. Parser and policy share a filter-control predicate that excludes unnamed generic checkboxes.

Minor coverage deferred: choosing a non-first toggle, preserving an anchor target after preventDefault, and rejecting a changed listing href. Existing code paths are retained and reviewed, but these extra explicit fixtures are not included in this patch.

Scope decision: retention redesign and multi-site customer-search scheduling remain separate; cost is that those existing limitations persist. The current fixes address observed learning failures without changing service ownership or signing.

## Validation record
- Full final JVM suite: 198/198 passed, including both review regressions observed failing before correction.
- Document boundary suite: 3/3 passed.
- Browser fixture suite: 9/9 passed in GitHub run 36795382914, including unattended listing navigation and unchanged manual popup behavior.
- Android unit tests and debug APK build: passed in that run.
- Signed release pipeline re-runs these gates before publication. Phone acceptance remains pending.
