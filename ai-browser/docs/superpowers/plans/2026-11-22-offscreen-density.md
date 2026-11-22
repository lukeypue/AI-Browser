# Offscreen viewport density implementation plan
1. Verify latest phone report schema/hash and current source, preserve running infrastructure.
2. Isolated actual Android component probe: compile exact production browser classes and assets into separate package com.aibrowser.viewportlab; fixed checked emulator-5580, separate fresh profile, numeric-only result with matching run UUID, exact owned HTTPS fixture URL and synthetic title. No install over pinned mainapp/testAPK, no synthetic memory mutation, no teacher/key access. Verify baseline geometry fails intended logical412x915 viewport.
3. Add focused density conversion tests before implementation; fix only reusable offscreen surface geometry using owning Context display density. Verify actual Gecko CSS viewport before/after at same density. Keep reader script and report schema unchanged.
4. Add deterministic browser coverage for tiny vs normal responsive layouts and retained synthetic learning where warranted, preserving all frozen exams.
5. Full core/app/runner/receiver/browser/Android-fixture tests, Android debug build/lint. Independent code and operations review. Fix all important findings.
6. Normal fast-forward source publication, verified stable signed release pipeline, all CI checks and artifact certificate/hash checks; no version/signing/workflow edits.
7. Reuse guarded native deployment and existing City/uploader/review, verify completed cycle and persistence on new revision. Never claim phone improvement until physicalphone report arrives.

The initial existing-instrumentation prototype was build-only, never installed, and restored exactly to HEAD. The isolated component harness replaces that approach to preserve pinned app/test packages during reproduction. Fixed fixture uses ordinary validated HTTPS; TRAIN prevents mutation and is not described as a host allowlist. Numeric measurements only leave probe profile.
