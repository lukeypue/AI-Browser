# Windows Site Brain browser trainer

## Intent and authorization
Luke wants to use his Windows 11 x64 laptop (Ryzen 5 6600H, 32 GB RAM) to
accelerate the existing AI Browser brain with unattended practice. He explicitly
requested the build and instructed us to continue until done without approval
stops. Extend the existing renderer boundary; preserve the released Android app.

## Architecture
An offline Kotlin lab entry point runs the same production BrainEngine,
SpsParser, Executor, Verifier, and SkillCompiler against a Node/Playwright
renderer. Actual HTML controls and the shipped content.js produce observations
and execute commands. Hidden marketplace state supplies independent scoring;
truth is never included in the student's page observation. Separate browser
contexts and memories isolate workers. Synthetic hosts end in .sim.invalid;
all browser requests are intercepted, so practice cannot access real accounts.

The recommended approach is a desktop practice lab with a real browser. A
wrapper around existing wire-format tests alone would miss DOM extraction.
Live-site or cloud workers add auth, cost, and privacy requirements; defer those
until controlled browser practice is validated.

## Learning and evidence
Each batch forks the frozen input memory for baseline and held-out evaluation.
Training alone can write retained synthetic structural/procedural memory.
A deterministic teacher uses only visible roles and the goal; no external AI
calls or API keys. Save an atomic checkpoint per worker and a bounded report
history. Each batch records objectives, false claims, hard-limit violations,
actions, teacher demonstrations, compiled reuse, source digest, and browser
version. Progress counts practice; it never claims real-site mastery.
Search with missing axle evidence must return possible matches without calling
the axle verified. Hard price/mileage limits remain enforced on listing evidence.
Failed eval does not update training memory or claim successful transfer.

## Desktop operation
Default four workers, configurable 1–8; each has a bounded batch and request
deadline. Repeated worker errors stop that worker with a visible explanation.
One-click setup downloads portable Node 24 and Temurin Java 17 with SHA-256
verification, then installs pinned Playwright/Chromium locally. No administrator
rights, system PATH changes, or permanent Windows policy changes. Start/Stop
launchers operate through a loopback dashboard. Display CPU/RAM use, batch
results, and explicit synthetic-only limitations. Keep-awake applies only while
the launcher is running and is released on exit. Screen may turn off.

No automatic phone-memory import and no APK update in this task. The result is
a ZIP Luke extracts and runs, plus simple instructions. Windows setup and a
four-worker bounded smoke run must be exercised on Windows CI before delivery.

## Acceptance
Real DOM search/filter/detail/pagination cases; missing filters, stale-document
refusal, auth wall, disabled/no-op submit, network denial; independent oracle;
memory restart and eval isolation; bounded bridge timeout; graceful stop,
duplicate-start exclusion, report retention, paths with spaces; production jar
excludes all lab classes. Existing core/Android checks remain green.
