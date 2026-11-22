# Network path classification implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline.

**Goal:** Correct host/path confusion without relaxing mutation policy.
**Architecture:** Parse request pathname once; malformed mutation URLs stay UNKNOWN_MUTATION. Exercise actual background listener in Node vm with stubbed browser transport.
**Tech stack:** Existing JavaScript/Node and Android project; no dependencies.
**Spec:** ../specs/2026-11-22-network-path-classification-design.md

## Global constraints
Preserve secrets, signing, workflow files, existing timers/uploader/review, human boundaries and failed strategy memory. No phone installation. Bridge version3.1.12; content.js unchanged.

## Review focus
Hostname/action-word confusion; actual write path plus read-looking name; unknown POST default; malformed URL failure behavior; OFF/ASSIST grant behavior. Tests pin each behavior.

### Task1: Production-listener classifier fix
- [ ] Add network-path-classification.test.mjs covering the five review cases plus neutral-read control; run RED.
- [ ] Change only background.js path parsing/classification; invalid URL→UNKNOWN_MUTATION. Advance manifest bridge version.
- [ ] Run complete new suite GREEN and all required source/Android/City checks.
- [ ] Obtain independent exact-source review and commit clean candidate.

### Task2: Existing pipeline and guarded runtime
- [ ] Verify stable head still expected; no force push; prepare bound attestations and use existing release controller only after gates.
- [ ] Verify all three CI, APK source/package/certificate/reader+background bytes and version.
- [ ] Review runtime helpers against current recovered-old native state; preserve failed863cold evidence. City source/report/upload verified; native candidate must pass warm+cold+memory gates or restore verified old and report blocker.
