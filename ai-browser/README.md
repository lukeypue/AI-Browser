# AI Browser — Site Brain v3

An Android browser with a brain: it learns how marketplace sites work, finds deals from a
plain-English request, reads listings for the details a site cannot filter, and returns
verified matches with evidence — while keeping every bit of your data on your phone.

## What it does

- **Deep Search** — "Ford Expedition under 8k under 150k miles with a 3.73 axle" becomes typed
  requirements. On each site the brain searches, applies the filters it can, reads listing
  cards, opens likely matches and reads descriptions for the rare details. You get
  *verified* matches (with the exact quote), *possible* matches (detail not mentioned) and
  *near misses*.
- **One browser, one sign-in** — sign in to a site once (Google / Facebook / Apple popups
  work); the same session is used for searching and learning. Nothing is copied. Sites that
  remember a solved CAPTCHA remember it here too.
- **Overnight learning** — a curriculum per site (search, filters, pagination, details,
  descriptions) runs in the background with the screen off. It only reads and filters.
- **Message a seller** — the brain opens the listing and fills your message, then shows it
  to you. Nothing is sent until you tap **SEND IT**; that permission is single-use.
- **Teach mode** — show it a control once; it becomes a reusable skill.

## Privacy

- Nothing is sold, uploaded or collected. No server of ours, no analytics, no ads.
- Sign-ins live only inside the browser engine's profile on this phone (as in any browser).
  The app has no cookie or password access. **Forget all sign-ins** wipes them.
- The brain stores structure, not content: page types, which control plays which role,
  success statistics. No listing text, no seller names, no searches.
- If you add an AI planner key, only a short redacted description of the page structure is
  sent when the brain is stuck — never sign-in or verification pages, never personal data.
  The key is encrypted with Android Keystore and stays on the phone.

## Build

```
gradle :brain-core:test        # the brain (pure Kotlin, ~50 tests incl. an end-to-end fake marketplace)
gradle testDebugUnitTest       # app unit tests
gradle assembleDebug           # debug APK (arm64)
```

To re-record the extractor fixtures after changing `app/src/main/assets/sitebrain/content.js`:

```
cd brain-core/src/test/harness && npm i playwright && node extract.mjs
```

## Layout

- `brain-core/` — the Site Brain (see `docs/BRAIN_ARCHITECTURE.md`)
- `app/src/main/assets/sitebrain/` — the bundled WebExtension (content script + network guard)
- `app/src/main/java/com/appgate/tv/browser/` — GeckoView engine, popups, prompts, handoff
- `app/src/main/java/com/appgate/tv/service/BrainService.kt` — background engine
- `app/src/main/java/com/appgate/tv/ui/` — Search, Learning, Browser screens

`CHANGES.md` lists what changed from v6 and why.
