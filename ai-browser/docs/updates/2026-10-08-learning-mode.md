# 7.2.7 — learning mode and detail opportunities

The October 8 diagnostics from 7.2.6 showed no new verified lessons: listing actions left Cars on results, and OfferUp repeatedly reported `not_observed_available`.

## Corrections

- Propagate the session mode to content ports on connection and mode changes, and include it in every command. A fresh document applies TRAIN before its action. This activates the observed listing href navigation introduced in 7.2.6, and the existing composer/commit input guard.
- An unfinished detail lesson may use a live listing-opening prerequisite despite a saved ABSENT observation from another page. Existing retry deadlines remain authoritative; opening a listing does not complete the detail lesson.
- Keep an already open detail page when description practice is the currently eligible selected lesson. Avoid replacing the opportunity with another search probe. Back practice first reestablishes a results checkpoint in its new lesson ledger.

Saved lessons and site models retain their existing format. Verified postconditions remain the only source of success credit. Network interlocks and human-only boundaries remain in place.

## Validation

Regression fixtures cover mode carried by a new-document command, blocked Send controls and OFF mode restoration, persisted absent detail opportunities, lesson cooldowns, and preservation of a current detail page. Full JVM, browser, and Android build checks run before updater publication.

Real-site learning improvement still requires fresh device logs after installation; synthetic fixtures establish the corrected behavior, not a claimed live-site success rate.
