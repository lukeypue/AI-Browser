# Changes in 7.2.4 (Simulation-tested search fixes)

- A typed query on the home page no longer counts as a completed search; results
  must actually be visible.
- Missing controls in an open filter panel stop repeated attempts to reopen it.
  Requested limits still apply when checking listing cards and details.
- A refused stale page or target triggers a fresh observation and bounded retry.
  The brain checks the current site, page, and listing identity before trying again.
  Seller messages and other consequential actions are never automatically retried.
- Added an offline simulation trainer for development. Practice memory stays
  separate from your phone's real-site knowledge, and the trainer is excluded
  from the app's production code.

These changes have synthetic and automated regression coverage. Real marketplace
behavior and background operation still need phone testing. Existing browser,
sign-ins, stored knowledge, service, and update-signing setup are preserved.

# Changes in 7.0.0 (Site Brain v3)

## Why the Google sign-in went white after your email — and the fix

1. **Google refuses OAuth inside Android WebView.** Google blocks "embedded web views"
   (the `disallowed_useragent` policy; WebView announces itself with a `wv` token). The overnight
   learner ran in WebView, so any Google step there was dead on arrival. → Everything now runs in
   **GeckoView**, which presents itself as Firefox for Android — a real browser to Google.
2. **The sign-in popup was never closed.** The Gecko sign-in screens created the popup
   (`onNewSession`) but never handled the popup *closing* (`onCloseRequest`). When Google finished
   and called `window.close()`, the app kept showing the dead popup: a white screen, with the
   2-step page happening where you could not see it. → `BrainSession` keeps a popup stack, returns
   to the opener when a popup closes, and the human "DONE" button also closes stray popups.
3. **No PromptDelegate.** Without one, GeckoView silently drops every `<select>`, confirm and
   HTTP-auth dialog — "pick a 2-step method" dropdowns and Make/Model selects did nothing. →
   `BrainPromptDelegate` shows real dialogs when you are looking, resolves them safely when not.
4. **Two cookie jars.** Sign-in was GeckoView, learning was WebView, so "remember me" could never
   carry over. → One `GeckoRuntime`, one profile, one identity for sign-in, searching and
   learning. CAPTCHA "memory" is the site's own cookie in that profile.
5. **Navigation blocking during sign-in.** The old sign-in screen denied any host not on a short
   list; identity providers bounce through several. → A person driving the browser is never
   blocked; only non-web schemes are handed to Android.

## The brain (new `brain-core` module, replaces `sitebrain-core`, `sitebrain-lab`, `app/.../sitebrain`)

Implements the external architecture review end to end:

- **Semantic Page State** replaces DOM fingerprints as the unit of knowledge.
- **Typed actions with postconditions**; the verifier is the only source of reward.
- **Skills = programs over roles + per-site bindings** with Beta statistics and decay.
- **Site profiles as data** instead of per-site mini-brain code.
- **Curriculum + verified outcomes** instead of dwell timers and plateau logic.
- **Task ledger** checkpointed before every action and after every verification.
- **Goal parser** with hard / soft / rare (text-evidence) constraints, contradiction warnings,
  and UNKNOWN as a first-class verdict; results ranked into verified / possible / near-miss with
  evidence quotes.
- **Planner** (OpenAI Responses API with strict JSON schema, or Anthropic Messages) consulted
  only on a miss, with budgets, cooldowns, a per-state cap, and output validated against the live
  page; verified programs are compiled into skills.
- **Three interlocks** for consequential actions: executor grant check, network guard in the
  extension background script, and an input guard in the content script.
- **Consolidation** with retirement, version-drift shadowing and Brier-score quarantine.
- **Privacy by construction**: redaction in the extractor, no content in memory/episodes/
  diagnostics, auth pages never modelled or sent anywhere.

## Browser / service

- `BrainService` (foreground) owns the runtime, session and engine thread; wake lock only while
  working; watchdog recovers the renderer after 4 minutes without progress; Android 15
  `onTimeout` handled by pausing cleanly.
- Off-screen `GeckoDisplay` keeps pages "visible" to their own scripts while unattended
  (infinite scroll keeps working).
- Human handoff shows the very same session on screen; the engine resumes where it stopped.

## Removed

- WebView learner, `OvernightLearningActivity`, `LearningActivity` (old), `HumanSignInActivity`,
  `FacebookGeckoPilotActivity`, `ListingActivity`, `LearningKeepAliveService`, Facebook mini brain,
  the `sitebrain-lab` scout, GitHub log-request polling, TikTok/Fire TV leftovers.

## Not done yet / next

- The record/replay harness measures correctness; the *metrics* from the review (transfer gain,
  action efficiency, planner calls per task, brittleness) are the next thing to add on top of it.
- Real-device verification of the four training sites' quirks (KSL's path-encoded facets and
  cascading Make/Model, OfferUp's drawer, Facebook's GraphQL endpoints) — the profiles seed these,
  the brain learns the rest, and Teach mode fills gaps.
