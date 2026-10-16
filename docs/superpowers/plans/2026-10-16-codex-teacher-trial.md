# Actual AI proposal trial

Isolated lab adapter accepts a generated typed JSON proposal, parses it through the real Planner, restricts it to one mandatory non-submit DISMISS/CLOSE step with DialogClosed, then runs the existing frozen Chromium replay through BrainEngine/verifier/compiler. Held-out evaluation gets fresh frozen-memory copies and no model client. Unsafe/optional proposals are rejected by tests.

Actual supported Codex exec request through the existing ChatGPT sign-in returned a proposal. No authentication file was read or copied. The CLI trial used that exact output, SHA25666c0c456500200374274249fefeafdf89d5f59e1b86f578181e4b2f1f15b5e57. Result:1compiled procedure,4held-out reuses,0evaluation calls,0unsafe/false mastery. Baseline already closes4/4; no measured closure gain or phone/real-site fix. This is recorded synthetic replay, not live Android or automatic production teaching.

Full core suite and focused proposal tests passed. To reproduce with a separately generated proposal, run gradle -p ai-browser/brain-core test codexDialogClinic -PteacherProposal=/absolute/proposal.json -PteacherTrialReport=/absolute/report.json. Existing timer, source deployment, APK version/signing/workflows are unchanged.
