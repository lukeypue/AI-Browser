# Windows Browser Trainer Implementation Plan

> Use superpowers:executing-plans to implement inline; final whole-branch review.

**Goal:** Deliver a tested Windows browser practice station with four workers.
**Architecture:** Lab-only Kotlin renderer bridge and trainer; Node-owned HTML
marketplace and independent truth; loopback dashboard and portable setup.
**Tech Stack:** JVM 17, Kotlin 2.2.10, Node 24, Playwright 1.62.1, PowerShell 5.1.
**Spec:** ../specs/2026-10-04-windows-trainer-design.md

## Global Constraints
- Existing Android app, production code, signing, and version allocation unchanged.
- Four workers by default; accepted worker range 1–8.
- Browser requests restricted to intercepted .sim.invalid fixtures; no real login.
- No external model calls or costs; no synthetic memory import into Android.
- Freeze/fork evaluation; only training contributes to retained worker memory.
- Explicit bounded deadlines, stop controls, durable checkpoints and bounded reports.

## Review Focus
- Windows paths with spaces and child-process ownership/cleanup.
- Oracle failure never shown as success or propagated into phone knowledge.
- Resume retains training memory but eval writes never enter its checkpoint.
- Setup/download failure leaves a retryable folder and a useful message.
- Dashboard controls reject nonlocal requests and unexpected origins.

### Task 1: Browser marketplace and bridge
Files: desktop-trainer/market.mjs, renderer.mjs, tests/market.test.mjs;
lab BrowserRenderer.kt; lab tests BrowserBridgeTest.kt.
Interfaces: createMarket(scenario), HTML/render/events/truth; newline JSON RPC;
BrowserRenderer(request timeout) implements Renderer and exposes separate truth.
- [ ] Test catalog/filter truth, DOM controls/events, stale identity, auth/no-op,
  blocked network and timeout before implementation; observe failures.
- [ ] Implement isolated real-browser environment, strict bridge, deadlines and close.
- [ ] Run Node browser tests and Kotlin bridge tests; commit.

### Task 2: Durable browser learning experiment
Files: lab BrowserTrainer.kt, BrowserMain.kt, lab tests BrowserTrainerTest.kt;
brain-core/build.gradle lab distribution task.
Interfaces: run batch(input snapshot, train count, eval count, renderer), output
report and training-only checkpoint; executable fat lab JAR.
- [ ] Test evaluation isolation, independent verdict scoring and checkpoint
  corruption handling; observe failure.
- [ ] Implement role-only demonstrations and pre/post held-out evaluations.
- [ ] Verify compiled learning/reuse and full existing core suite; commit.

### Task 3: Windows launchers, dashboard and package
Files: desktop-trainer/{runner.mjs,dashboard.html,Setup.ps1,Run.ps1,*.cmd,
package.json,package-lock.json,README.txt}; scripts/package-desktop-trainer.py;
.github/workflows/windows-trainer.yml.
Interfaces: runner --workers 1..8 --rounds N, loopback status and stop; portable ZIP.
- [ ] Test config validation, atomic state, stop/duplicate-start, report retention
  and four-worker orchestration before implementation.
- [ ] Implement local setup, dashboard, keep-awake and recoverable worker loop.
- [ ] Package and exercise Windows setup plus four-worker smoke via CI.
- [ ] Final review, fix material findings with RED/GREEN, upload verified ZIP.
