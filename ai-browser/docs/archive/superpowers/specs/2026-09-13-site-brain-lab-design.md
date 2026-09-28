# Site Brain Lab Design

## Goal

Move broad website learning out of the Android search session into a separate Site Brain Lab that can repeatedly learn public website interfaces, verify safe workflows, measure structural coverage, and publish compact reusable Site Knowledge Packs for AI Browser.

## Relationship to AI Browser

Site Brain Lab is the shared website-understanding layer. AI Browser remains the Android personal-assistant layer.

The Lab learns generalized website structure. Android downloads that knowledge and uses it during user searches. Authenticated/private areas remain on-device; the phone may later contribute privacy-safe structural observations without uploading account data.

## Architecture

The Lab has five focused components:

1. **Explorer Runner** — opens configured public entry points and performs only safe, reversible navigation/search/read actions.
2. **Semantic Perception** — converts each page into the same semantic concepts used by Android Site Brain: page type, sections, controls, labels, routes, result/detail structures, filters, sorting, pagination and challenge boundaries.
3. **Verifier and Coverage Engine** — proves action outcomes, deduplicates states, records failures, detects stagnation and calculates coverage of discovered safe interface functions.
4. **Knowledge Pack Builder** — exports versioned per-domain packs containing generalized semantic nodes, verified safe transitions, extractor hints, confidence, timestamps and compatibility metadata.
5. **Pack Consumer** — Android imports packs into local Site Brain storage, merges them with newer on-device evidence and never overwrites stronger local verified knowledge with weaker remote hints.

## Exploration Model

The Lab explores breadth-first from configured public entry points. It keeps following newly discovered safe branches until one of these conditions is met:

- all currently discovered safe interface branches have been classified and either verified or explicitly unresolved;
- the site reaches the configured time/action/page budget;
- a login, CAPTCHA, MFA, security or access-control boundary blocks further public exploration;
- repeated equivalent states indicate stagnation.

A later run resumes unresolved branches rather than starting over.

Coverage means coverage of discovered safe searchable interface functions, not every content item on an infinite marketplace. A marketplace can reach 100% structural coverage while containing millions of listings.

## Safety Boundary

Automatic Lab actions are limited to navigation, public search, categories, reversible filters, sorting, pagination/infinite-scroll discovery, opening public listing/detail pages, expanding read-only details and back navigation.

The Lab never automatically buys, bids, checks out, sends messages, contacts sellers, posts, uploads, deletes, follows/subscribes, changes accounts, accepts agreements, submits sensitive forms, or bypasses CAPTCHA/MFA/security/anti-bot systems.

When a public crawler reaches a protected boundary, that branch is recorded as `HUMAN_OR_AUTH_REQUIRED` and exploration continues elsewhere.

## Public vs Private Learning

Shared Site Knowledge may contain:

- semantic page types and category relationships;
- labels/roles and generalized locator hints;
- verified safe navigation/search/filter/sort/detail transitions;
- result-card and detail-field structures;
- pagination/infinite-scroll behavior;
- change/repair history and anonymized reliability metrics.

It must never contain passwords, cookies, session tokens, phone numbers, email addresses, private messages, seller conversations, payment information, CAPTCHA answers or private account content.

For Facebook Marketplace and similar login-heavy sources, the Lab learns public structure only. AI Browser learns authenticated structure after the user legitimately signs in. A future opt-in contribution flow may upload only sanitized structural observations.

## Knowledge Pack Format

Each pack is versioned and domain-scoped. It includes:

- schema version;
- domain and supported host aliases;
- generated/last-verified timestamps;
- Site Brain readiness and structural coverage;
- semantic nodes and page fingerprints stripped of private content;
- verified safe transitions and confidence;
- semantic extractor/control hints;
- unresolved branches and protected boundaries;
- source revision and integrity digest.

Android treats downloaded packs as shared evidence, not absolute truth. Live verification can repair stale knowledge.

## Initial Site Set

The first Lab release focuses on sources that matter most to the existing vehicle/use-market test flow and that expose useful public pages:

- KSL Cars
- eBay
- Craigslist
- AutoTrader
- Cars.com
- CarGurus
- Edmunds
- TrueCar
- CarMax
- OfferUp where public access permits
- Facebook Marketplace public surfaces only; authenticated learning stays on Android

The architecture remains generic so later categories such as toys, furniture, rentals and general shopping reuse the same site knowledge.

## Continuous Learning and Release

The Lab stores checkpoints and produces a new pack only after verification. Packs are artifacts that can be inspected and tested before Android consumes them. During the early development phase, GitHub can store/version the code and pack artifacts, while the crawler runtime remains separable from GitHub Actions so it can later move to an appropriate continuously running service without changing the pack contract.

The first implementation is a deterministic runner and fixture/live-safe exploration harness, not a claim that every public site has already been exhaustively crawled from this chat environment. Real-site learning requires a runtime with browser/network access and must respect site access controls and terms.

## Android Changes Alongside the Lab

The next Android increment also fixes issues exposed by v5.1 testing:

- remove the one-autonomous-action-per-site learning governor;
- keep ordinary HTTPS marketplace navigation inside AI Browser instead of handing it to installed marketplace apps;
- safely reject or web-fallback app-only/deep-link intents during autonomous exploration;
- fix dangling conjunctions such as `3.73 axle and` in parsed requirements;
- write detailed privacy-safe learning/test logs;
- provide a user-visible Share Learning Data flow;
- import and merge Site Knowledge Packs.

## Testing

Lab tests cover safe-action policy, deduplication, budgets, stagnation, challenge boundaries, resumable checkpoints, coverage calculation, pack serialization/integrity and private-data sanitization.

Android tests cover pack merge precedence, schema compatibility, in-WebView URL policy, intent/deep-link blocking/fallback, parser cleanup and export-log sanitization.

A pack is releasable only after deterministic tests pass and its recorded transitions are evidence-backed. Live-site changes lower confidence and schedule re-exploration rather than silently remaining trusted.

## Success Criteria

The first Site Brain Lab milestone is successful when:

1. a standalone runner can learn a public site from configured entry points without Android;
2. exploration continues across discovered safe branches until coverage/budget/boundary termination;
3. progress is checkpointed and resumable;
4. protected/consequential actions are never auto-executed;
5. a versioned sanitized Site Knowledge Pack is produced;
6. Android can import/merge the pack and live-verify it;
7. Android can export a privacy-safe learning report for debugging;
8. ordinary eBay/web marketplace links remain in the app during autonomous exploration;
9. the existing Android unit/build pipeline remains green.
