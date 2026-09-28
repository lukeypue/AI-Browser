# Site Brain Trainer — Standalone Android Training Design

Date: 2026-09-13
Status: Approved architecture, implementation pending plan

## Purpose

Build Site Brain as a standalone Android training program before embedding it into AI Browser. The Trainer learns how starter websites expose search, categories, filters, sorting, pagination/infinite scroll, results, listing details, and alternate safe navigation paths. It stores reusable semantic knowledge rather than brittle click scripts.

The target is at least 95% verified coverage of each starter site's discovered safe searchable interface. This is structural coverage, not 95% of every listing or every URL on a dynamic marketplace.

## Architecture

Use a hybrid system with Android as the authoritative authenticated trainer and the existing public Site Brain Lab as a complementary public scout.

1. **Android Site Brain Trainer** runs on the phone under the same WebView/mobile conditions as the eventual AI Browser. The user performs legitimate login, MFA, CAPTCHA, or other human verification when a site requires it. After that, the Trainer can continue safe exploration using the legitimate authenticated browser session.
2. **Public Site Brain Lab** runs independently against publicly accessible site surfaces. It continuously tests and learns public structure without authentication.
3. **Site Knowledge Packs** are the shared semantic interchange format. Public and phone learning merge by domain, semantic action, page type, evidence, confidence, and recency. Private session material never enters a pack.
4. **AI Browser** later consumes the mature packs and keeps learning during real use rather than relearning each site from zero during every search.

## Starter Sites

Initial training targets are KSL Cars, Facebook Marketplace, eBay, Craigslist, AutoTrader, Cars.com, CarGurus, Edmunds, TrueCar, CarMax, and OfferUp where their normal user-facing interfaces permit access.

A site that blocks the public scout is not considered failed. Its public-scout coverage can remain low while Android authenticated/mobile coverage advances independently.

## Android Trainer Experience

The standalone app presents a simple site dashboard. Each site shows readiness, verified structural coverage, last successful training time, current activity, and whether human attention is required.

The primary command is **TRAIN ALL SITES**. Training cycles through sites and revisits the weakest verified capabilities first. A user can also train one site. The app is designed to be left plugged in with the screen/session available while training, subject to Android lifecycle limits.

When login or human verification is encountered, the Trainer pauses that site and clearly asks the user to complete the site's legitimate flow. It never attempts to solve or bypass CAPTCHA, MFA, security checkpoints, or anti-bot controls. After the user succeeds, training resumes from the authenticated page.

Training continues across cycles until the site reaches the verified target or is blocked by a protected boundary. A protected boundary is reported separately and never manipulated to inflate or reduce structural coverage.

## Autonomous Learning Loop

For each site:

1. Observe and classify the current page.
2. Discover visible semantic controls and safe candidate actions.
3. Prefer missing or low-confidence capabilities.
4. Execute one safe reversible action.
5. Observe the resulting page/state.
6. Verify that the expected semantic transition actually occurred.
7. Promote verified knowledge; reduce confidence or repair stale locators on failure.
8. Add newly discovered safe paths to the exploration frontier.
9. Detect loops/stagnation and choose another frontier path.
10. Checkpoint frequently and continue until target or budget.

The system learns meanings such as `SEARCH`, `OPEN_CATEGORY`, `SET_FILTER`, `SORT`, `NEXT_RESULTS`, `OPEN_DETAIL`, and `BACK_TO_RESULTS`. CSS/XPath/DOM hints are replaceable locators, not the knowledge itself.

## Safe vs Consequential Actions

Autonomous training may perform read-oriented, reversible actions: search, navigation, category browsing, filters, sorting, pagination/infinite scroll, opening listing details, expanding read-only information, and back navigation.

It must not autonomously buy, bid, order, checkout, pay, message/contact a seller, post/publish/upload, delete/remove, follow/subscribe when account-changing, alter account/profile/security settings, accept agreements, or submit sensitive forms. Those actions are classified as consequential boundaries.

## Authentication and Privacy

The user's authenticated WebView session remains local to the Android device. Passwords, cookies, session tokens, authorization headers, MFA material, CAPTCHA answers, payment data, private messages, and account-specific private content are never exported into Site Knowledge Packs or learning reports.

Exportable learning contains structural facts such as page type, sanitized route/template, semantic control intent, transition outcome, confidence, failure class, coverage, and timestamps. Sanitization removes obvious email addresses, phone numbers, secrets, and session-bearing query parameters.

## Coverage and the 95% Goal

Coverage measures verified searchable capabilities, not raw click count or URL count. The denominator is the set of safe structural capabilities/routes discovered for that site, including search entry, major searchable categories, filters, sort, result traversal, pagination/infinite scroll, detail templates, alternate paths, and return navigation.

A capability only counts as verified after successful outcome verification. High confidence requires repeated success across separate training observations. The Trainer may report 95%+ only when the required search funnel is present and the remaining uncovered items are noncritical safe structural items. Login/security/consequential boundaries are tracked separately and are not silently counted as learned.

The target is not permanently fixed: if a later run discovers a new safe structural capability, the denominator can grow and the displayed percentage can fall until the new path is verified. This prevents a false permanent 100%.

## Continuous Training

The Android Trainer runs repeated training cycles while active. It checkpoints after meaningful transitions so interruption does not erase progress. Android lifecycle/background restrictions mean the phone cannot honestly be promised unlimited 24/7 WebView execution while the OS suspends or kills the app; the Trainer will resume from its checkpoint when active again.

The public Lab can run scheduled repeated scout jobs independently. A future always-on host may run the public Lab continuously, but GitHub Actions is treated as scheduled training/verification rather than falsely described as an always-on server.

Each cycle prioritizes: missing critical capability → previously failed/stale path → low-confidence path → newly discovered path → periodic re-verification of mature paths.

## Knowledge Merge

Knowledge is keyed by domain plus semantic page/action identity, not raw selectors. Merging prefers newer repeated verified evidence while retaining alternate working paths. A public pack cannot overwrite stronger authenticated/mobile evidence merely because it is newer. Failed observations decay confidence and can trigger locator repair.

Imported packs are schema-versioned and validated before merge. Unknown/newer incompatible schemas are rejected safely. Future pack signing/checksums can be added before remote automatic distribution.

## Error Handling

The Trainer distinguishes normal failures from protected boundaries: navigation timeout, stale control, changed layout, no-op action, site error, authentication required, human verification required, access blocked, and consequential action blocked.

Timeout/stale/no-op failures trigger repair or alternate-path exploration. Authentication/human verification pauses that site's autonomous branch. Access blocks are recorded without evasion. Consequential actions are never executed by the trainer.

Stagnation detection prevents repeated clicking of the same ineffective controls. Per-site action/time/page budgets prevent runaway exploration; a later cycle resumes the remaining frontier.

## Testing and Verification

Core semantic policy remains unit-testable outside WebView. Android tests cover safety classification, navigation policy, pack import/merge, sanitization, coverage math, checkpoint/resume, prioritization, and outcome verification.

The public scout has its own safety/coverage tests and live artifact runs. Live percentages are treated as observations, not test fixtures, because websites change.

Before a Trainer release, require: Site Brain Lab tests pass, Android unit tests pass, debug APK builds, no protected/consequential action is executed by tests/policies, pack round-trip/import works, checkpoint/resume works, and the dashboard never labels unverified paths as verified.

## First Implementation Scope

The first standalone Trainer will reuse the existing Android Site Brain engine rather than fork a second brain. Work will:

- separate training orchestration from AI Browser search UI;
- add a Trainer dashboard and Train All Sites loop;
- persist resumable per-site frontiers/checkpoints;
- integrate the existing web-navigation policy so normal web links stay in WebView and app-only handoffs are blocked or use safe web fallbacks;
- merge public Site Knowledge Packs into local brains;
- add privacy-safe learning report export;
- train authenticated sites only after the user legitimately establishes the session;
- improve detail/results/filter recognition and repeated verification;
- keep the public Lab running as a complementary scheduled scout.

AI Browser integration comes after the standalone Trainer demonstrates strong verified knowledge. The Trainer and AI Browser will share the same Site Brain core and pack contract so this is not throwaway work.

## Success Criteria

The standalone Trainer is successful when it can resume learning after interruption, safely cycle starter sites, pause cleanly for human authentication/security, merge public knowledge, repair changed paths, and produce trustworthy per-site coverage. The practical release target is 95%+ verified searchable-interface coverage on reachable starter sites after repeated training, with protected boundaries honestly reported for anything that still requires the user or is inaccessible to automated training.
