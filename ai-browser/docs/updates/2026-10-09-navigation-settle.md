# 7.2.8 — navigation settlement and lesson prerequisites

The October 9 logs show 12.4 hours of 7.2.7 activity, 102 lesson attempts and no new completed lessons. Cars detail observations followed failed results-page opening checks; OfferUp repeatedly reported `not_observed_available`; Facebook repeatedly attempted search on a sparse SEARCH page.

## Changes

- A TRAIN listing navigation marks its departing document as pending. That document cannot answer the next settle request as IDLE while the destination response is loading. Gecko's existing port-loss handling then settles the destination. If navigation fails, the wait expires as UNKNOWN and releases the pending marker. Completion still requires verified postconditions.
- A safe dialog dismissal can serve as the prerequisite to an unfinished lesson despite a saved ABSENT opportunity. Cooldowns and human-only boundaries remain in effect. A completed dismissal skill does not complete another lesson.
- On an idle, unobstructed SEARCH page, use the profile's known results URL when different from the current URL. This avoids repeated synthetic search submission on an empty search context. Navigation remains host checked and creates no lesson credit.
- Idle rechecks include sanitized pending lesson states, live target capabilities, cooldown flags, and structural page counts. No URLs, queries, listing text, names, or option values are included.

## Verification

The delayed-response browser fixture reproduces a departing page settling before navigation completes. Curriculum fixtures reproduce the completed-dialog prerequisite stall and the empty-search recovery gap. A recheck integration fixture validates useful structural evidence without leaking page values. Run the complete JVM/browser suites and Android CI before signed updater publication.

Saved learning remains compatible. Synthetic tests establish the corrected paths; fresh device diagnostics are required to measure real-site learning improvement.
