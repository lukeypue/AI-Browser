# 7.2.3 live-site learning repair

Goal: a private, reliable browser brain that learns verified ways to search difficult websites and compare deals without selling customer information.

Evidence: the two latest uploads are identical. 7.2.2 ran 56 minutes, finished seven partial attempts and learned zero lessons. Live public KSL inspection on October 1 confirms whole-card anchors with target=_blank and separate role=link location spans without href. Those spans are incorrectly classified as RESULT_ITEM. The location modal adds a search-like input, slider and close control, matching the logged overlay shape, though the phone's specific clicked element cannot be proven from old counts alone.

Requirements:
- Only navigable listing links receive RESULT_ITEM; a location link inside a card must not outrank its listing anchor.
- Facet lessons open the named price/condition accordion already on results. A general drawer opener cannot be replaced by Location or an unrelated facet.
- An unfinished lesson can use search as a prerequisite on a search page even when old opportunity records say ABSENT. Cooldowns and human holds remain enforced; prerequisite actions alone do not complete the lesson.
- A teacher receives the exact requested capability. Reject programs whose roles and final expectations do not satisfy that capability before execution.
- Optional OpenAI stronger teaching uses pinned gpt-5.4-2026-03-05, low reasoning, a 4000-token output ceiling and 90-second read timeout for 24 hours. It requires an explicit dashboard switch, defaults off, preserves saved settings, uses only the normal OpenAI endpoint and retains 6/hour, 24/day limits. No provider switching or hidden retries. A request may be more expensive; show this before activation.
- Add only structural role/key counts to diagnostics. No listing text, private queries, cookies or keys.
- Build and publish through existing signing automation. Tests cannot establish live phone success; request one fresh diagnostic export after installation.

Research: https://developers.openai.com/api/docs/models/gpt-5.4 and https://developers.openai.com/api/docs/models/gpt-5.4-mini (official docs inspected October 1, 2026). Full model token rates are about 3.3x mini; total cost also depends on tokens and reasoning.

Review fixes: concrete query repairs are normalized for the template check, named-facet repairs have a keyed contract, and the observed newUsed URL path retains the selected condition after its popup closes. Input text alone is not promoted to an applied condition. Local verification is recorded after the full post-review suite; live phone verification remains pending.

Local final validation: 212 core JUnit tests and three document-boundary tests passed. Browser/Android CI and signed updater publication are required before this release is declared available.
