# Android learning reliability update

Continue the signed 7.2.5 baseline and retain learning, accounts and teacher limits.
The two supplied exports overlap almost entirely; the later export contains one
additional event. Its 7.2.5 segment covers 5h33m, 181 lesson attempts and zero new
verified lessons. KSL Cars repeatedly failed opening items; OfferUp repeatedly
reported page_busy; teacher usage reached the existing 120/day cap.

Changes:
- TRAIN navigates current observed listing hrefs in the owned session rather than
  relying on synthetic click handlers. Changed hrefs are rejected; manual clicks
  retain popup behavior. Detail verification remains mandatory.
- Lesson opportunities and failed strategy keys consider the operation's relevant
  controls. Unrelated filters and option-count churn cannot reopen the AI breaker.
- Settle detection ignores image/media resource and thumbnail attribute churn,
  and offscreen lazy loaders. Visible loaders and meaningful DOM changes still
  keep the page busy. Two stable snapshots are still required by waitSettle.
- Extension version advances so existing installs receive the corrected bridge.

Regression evidence: original code failed canceled-click navigation, thumbnail
loading, offscreen-loader and unrelated cooldown-churn fixtures. Independent
review found an option-count cooldown regression; a failing test was added and
its cause removed. These are synthetic tests, not proof of live-site success.

Phone validation: update in place, restart learning, export diagnostics after
30–60 minutes. Look for verified open_item progress, fewer repeated repairs,
and newly available OfferUp lessons. No new daily/hourly allowance is introduced.
