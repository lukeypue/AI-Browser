# AI Browser Site Brain Design

## Goal

Replace the current mostly generic per-site extraction loop with a persistent semantic Site Brain that learns how an entire website is organized, explores safe navigation paths, verifies what controls actually do, remembers successful paths, repairs changed paths, and gives the Central Search Brain broad coverage rather than a single narrow query.

## Product Principle

The browser should behave more like a knowledgeable human researcher than a scripted scraper. It should understand a user's goal, explore multiple plausible categories and paths, narrow with native site filters, deep-read promising detail pages, verify requirements, and keep learning from the outcome of every safe action.

Core loop:

**Explore -> Understand -> Verify -> Remember -> Search -> Detect Failure -> Repair -> Learn**

## Safety Boundary

The Explorer may automatically perform safe, reversible, read/search/navigation actions such as:

- open navigation menus and categories
- use search boxes
- apply and remove search filters
- sort results
- paginate or scroll
- expand read-only details
- open result/listing/detail pages
- use back navigation
- inspect tabs and related categories

The Explorer must not automatically activate consequential actions such as:

- Buy / Bid / Order / Checkout / payment
- Send Message / Contact Seller / submit communications
- Post / Publish / Upload
- Delete / Remove
- Follow / Subscribe when it changes an account state
- account/profile/security changes
- accepting agreements or submitting sensitive forms

Consequential actions may only run from explicit user intent and a separate confirmation/permission flow.

The system must not bypass CAPTCHA, MFA, security checks, anti-bot systems, login protections, or site access controls. When a site requires a human verification step, the app pauses that Site Brain, lets the user complete the legitimate step in the WebView, then resumes automatically after the challenge clears.

The shared Site Brain must never contain passwords, cookies, session tokens, payment data, private messages, CAPTCHA answers, or other private account data.

## Research-Informed Direction

The strongest 2026 match to this design is **WebChallenger**, published in June 2026. Its PageMem/WebsiteMem architecture deterministically converts DOM pages into semantic sections, explores each website to build reusable memory of pages and element behavior, selectively reads only relevant regions, and uses reusable compound workflows. It explicitly reports generalization without per-site adapters. This strongly validates the Site Brain direction and gives us concrete ideas for semantic page decomposition, persistent site memory, selective observation, and reusable workflows. Its published code is MIT licensed, so we can inspect and adapt compatible ideas carefully rather than copying incompatible dependencies wholesale.

Other current browser-agent projects validate additional pieces of the architecture:

- Browser Use provides LLM-controlled browser agents and persistent browser automation patterns.
- Browser Harness focuses on a self-healing browser layer where agents can create reusable domain skills as they encounter missing interaction recipes.
- BrowserCode treats browser automation as a code-generation problem and reuses scripts learned during prior tasks.
- Skyvern combines Playwright, LLM reasoning, and computer vision to avoid brittle XPath-only automation. Its core repository is AGPL-3.0, so we should learn from the architecture without importing AGPL code into the Android app unless licensing is intentionally accepted.
- Stagehand exposes higher-level AI browser actions while retaining normal browser automation underneath.
- Playwright MCP exposes structured accessibility snapshots to LLMs, showing the value of semantic page representations rather than raw selectors.
- webagents.md explores the future possibility that sites may publish machine-discoverable tools directly.

AI Browser should combine these lessons into its own Android-first product model: persistent per-domain semantic graphs, evidence-backed action meanings, broad category/path exploration, on-device session continuity, safe human handoff, and search-specific coverage scoring.

## Architecture

### 1. Site Perception Engine

The perception engine converts the current WebView page into a semantic snapshot.

A snapshot includes:

- URL, title, host and normalized route signature
- visible text summary
- semantic DOM sections (header, form, navigation, list, table, article, main content and other natural boundaries)
- short section summaries so the Search Brain can skim before expanding details
- headings and landmarks
- forms and search inputs
- buttons and links
- accessible names and ARIA roles when available
- element text, href, input type, selected state and disabled state
- possible category/filter/sort/result/detail controls
- page scroll depth and pagination signals
- challenge/login/security signals
- a compact DOM fingerprint used for change detection and duplicate suppression

The first Android implementation uses WebView JavaScript DOM inspection plus accessibility/ARIA semantics available in page markup. The interface must be designed so visual perception can be added later without rewriting the Site Brain.

### 2. Semantic Action Model

Physical selectors are not the Site Brain's primary memory. Each discovered control becomes a semantic action hypothesis.

Example:

`OPEN_CATEGORY("Vacation Property")`

rather than:

`click("#menu > div:nth-child(4)")`

Each action record stores:

- semantic intent/type
- human-readable label
- source page signature
- one or more locator hints
- expected destination/page type
- observed postcondition
- safe/consequential classification
- confidence score
- success/failure counts
- last verified time
- version/revision metadata

Selectors remain replaceable implementation hints.

### 3. Site Graph

Each domain receives a persistent directed graph.

**Nodes** represent semantic page states such as:

- Home
- Search
- Category
- Subcategory
- Result List
- Listing/Detail
- Filter Panel
- Seller/Profile
- Login/Challenge
- Unknown

**Edges** represent verified actions and observed transitions.

The graph supports multiple valid paths to the same concept. A timeshare rental search can therefore explore Recreational Property, Vacation Property, Rentals, brand-specific pages, or other discovered branches rather than assuming one canonical path.

### 4. Explorer Agent

The Explorer maps a site breadth-first from known entry points.

For each page it:

1. captures a semantic snapshot
2. identifies safe action candidates
3. ranks unexplored actions
4. executes one safe action
5. records the pre-action state
6. waits for navigation or meaningful DOM change
7. captures the post-action state
8. determines what changed
9. classifies the resulting page/action meaning
10. stores the verified graph edge
11. returns or continues from the new state

The Explorer must deduplicate equivalent states using canonical URLs plus semantic DOM fingerprints. It must use configurable budgets for pages, actions, elapsed time, and repeated states so exploration is broad but finite.

### 5. Outcome Verifier

No action becomes trusted solely because it was clicked successfully.

The verifier compares the expected result with observed evidence:

- URL/route changes
- title/headings changes
- newly visible filters/results
- selected filter chips
- result count/text changes
- page-type changes
- detail/listing fields becoming visible

Confidence rises after repeated successful verification and falls after failures or site changes.

### 6. Learning and Confidence

Site Brain readiness levels:

1. **UNMAPPED** - insufficient knowledge
2. **LEARNING** - exploration in progress
3. **SEARCH_READY** - reliable search/category/result paths exist
4. **DEEP_SEARCH_READY** - major branches, results, details, filters and alternate paths are verified strongly enough for broad search

Coverage is not measured by raw click count. It is based on discovered major page types, navigable category branches, search/filter controls, result extraction, pagination/scroll behavior, detail extraction, alternate routes, and unresolved branches.

Unknown or contradictory behavior causes more exploration rather than false confidence.

### 7. Live Learning

Normal user searches reinforce or extend the Site Brain.

Every safe search can contribute:

- successful route/action evidence
- newly discovered category relationships
- result-card structures
- detail-page structures
- working filter semantics
- changed-control repair evidence

Search execution and exploration share the same graph and verifier.

### 8. Auto-Repair

When a previously trusted action fails:

1. preserve its semantic intent
2. re-perceive the current page
3. search for controls with equivalent meaning
4. try only safe candidates
5. verify the postcondition
6. replace or add locator hints after success
7. lower confidence if no replacement can be proven

The system repairs `MAX_PRICE_FILTER` rather than blindly trying to rediscover a former CSS selector.

### 9. Central Search Brain

The Central Search Brain understands the user's request and coordinates relevant Site Brains.

It separates:

- target concept/item
- hard constraints
- optional preferences
- location relevance
- terms likely to require deep detail-page inspection

It executes in waves:

**Wave 1 - fast known paths**
Use high-confidence sites and paths to start returning useful results quickly.

**Wave 2 - alternate paths**
Explore overlapping categories, related terms and additional sources.

**Wave 3 - deep verification**
Open promising detail pages and verify requirements not reliably available on result cards.

Early results can expand search vocabulary. For example, a timeshare request may discover brand/category terms such as WorldMark, Marriott Vacation Club, Wyndham, points, resort, owner rental or week, which can trigger additional targeted branches.

### 10. Search Coverage and Stopping Rules

The search should not stop because it found an arbitrary number of results.

It stops when:

- reasonable known paths are exhausted, or
- a configured coverage target is reached, or
- a user/time/resource budget is reached

The UI should report evidence such as:

- sites searched
- paths checked
- result cards inspected
- detail pages deep-read
- verified matches
- possible matches
- coverage confidence
- sites/branches requiring login or human verification

A result that cannot be proven to satisfy a hard requirement must be labeled **Possible Match**, not **Verified Match**.

### 11. Shared Site Knowledge vs Personal Data

Future server/community sync should only distribute generalized site knowledge such as:

- page types
- category relationships
- semantic control meanings
- verified safe navigation paths
- extractor patterns
- change/repair information
- anonymized reliability metrics

Personal WebView cookies/session state stays local unless a separate future account-sync design explicitly covers it. Passwords and sensitive private data are never uploaded as Site Brain knowledge.

## Android v5 First Increment

The next APK should establish the architecture without pretending that every site is already fully mapped.

It will add:

- persistent Site Brain models and local storage
- PageMem-inspired semantic DOM sectioning and compact section summaries
- safe-action classifier
- breadth-first Explorer with strict budgets
- outcome verification
- per-site readiness/coverage state
- learned path/action persistence
- live-learning hooks from searches
- a Search Brain that can choose multiple known paths per site
- UI showing learning/search status and coverage
- KSL as the first seeded/test domain while the generic Explorer is capable of learning other sites
- human handoff behavior preserved for login/security challenges

The existing generic anchor scraper remains only as a temporary fallback while Site Brain coverage is low. It must not label generic page text as a verified result.

## Non-Goals for This Increment

- server/community synchronization
- CAPTCHA solving or security bypass
- proxy/fingerprint/anti-bot evasion
- fully autonomous consequential actions
- claiming complete understanding of 100+ sites in the first APK
- heavy cloud browser infrastructure
- requiring desktop or Windows software for the user

## Testing Strategy

Unit tests cover:

- action safety classification
- semantic page-state fingerprinting
- semantic section decomposition
- duplicate-state detection
- graph persistence
- confidence/readiness transitions
- hard-filter evidence requirements
- result status: verified vs possible
- exploration budget termination
- auto-repair candidate replacement
- search-wave path selection

Integration-style fixtures use saved synthetic HTML pages to test:

- category -> results -> detail transitions
- alternate category paths reaching related results
- filters that change result state
- changed labels/selectors with the same semantic meaning
- login/challenge pause and safe resume
- dangerous controls never being automatically activated

GitHub Actions remains the build/test path for the Android APK.

## Success Criteria for v5

The v5 APK is successful when:

1. the app can build and persist a semantic Site Brain for a visited domain
2. it can automatically explore safe navigation/category/filter/search controls within configured budgets
3. it records verified page/action transitions rather than only raw selectors
4. repeated visits reuse learned paths
5. changed controls can be re-discovered by meaning and re-verified
6. consequential controls are never auto-triggered by exploration
7. search can try more than one learned path on the same site
8. hard requirements are never marked verified without evidence
9. login/CAPTCHA/security challenges pause for the user and resume safely
10. the APK passes unit tests and GitHub build successfully
