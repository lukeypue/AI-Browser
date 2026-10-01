# Learning stall repair

Spec: Improve verified learning on customer-authorized sites without collecting personal history or weakening human-only and network interlocks. Current baseline 0a286d3 (7.2.1). Evidence: 2341 events, 44 outcomes, zero new lessons, 1893 unavailable rechecks across 9h34m.

## Scope and completion
1. Reproduce filter drawer dismissal and accordion selection with Claude review regression tests. Preserve filter workspaces, recognize small drawers, select the requested toggle and actually apply changes. Verify whole skills, not helper clicks.
2. Reproduce observed listing anchors opening an unattended popup. In TRAIN only, activate observed same-site HTTP listing anchors in the current tab; keep stale-document checks and guard, preserve target outside TRAIN. This is a tested failure mode, not proof of the live Cars DOM.
3. Report typed idle reasons and wait until unfinished lessons leave cooldown. Preserve human holds and explicit restart. Add content-free observation counts.
4. Run core JVM and browser fixture suites, independent final review, then existing CI Android gates and canonical release automation. No signing/workflow/security changes.

## Review focus
Filter panel classification versus unrelated dialogs; apply step skipped by pending values; toggle grounding; same-tab click lifetime and DOM mutation; idle migration shortening new cooldowns; no private values in diagnostic fields.

## Rulings
Limit this update to the reproduced learning blockers. Retention redesign and customer-search scheduling remain separate work because they alter lifecycle contracts unrelated to this run; cost: those known limitations remain.

## Execution ledger
Task 1 complete: imported regressions failed on filter open/dismiss loop; corrected drawer flow passes.
Task 2 complete: GitHub run 36795090154 failed only the new unattended popup test (8/9); fixed run 36795382914 passed 9/9 and Android tests/build.
Task 3 complete: pending retry migration test passes; diagnostics contain structural fields only.
Final independent review: numeric accordion and unrelated-checkbox findings reproduced RED, fixed GREEN. Whole JVM suite 198/198. No additional Critical/Important findings.
Final minor (deferred): non-first toggle, canceled-click target restoration, mutated-href explicit fixtures.
Release uses canonical ai-browser-release.json and existing workflow; no signing or workflow changes.
