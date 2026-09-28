You are the independent reviewer for an AI-generated Site Brain patch.

Read AGENTS.md. Review the current branch diff against `ai-browser-v6-deep-search`.

Reject the patch if it:
- bypasses CAPTCHA, login, 2FA, paywalls, or access controls;
- touches signing material, secrets, GitHub workflows, release publishing, or adds dangerous permissions;
- sends personal browsing/session data off-device;
- hard-codes a brittle one-off behavior when a reusable capability is practical;
- lacks reasonable tests for behavior it changes;
- weakens existing safety boundaries;
- fails to compile conceptually or introduces obvious regressions.

If the patch is safe, reusable, and adequately tested, your FINAL line must be exactly:
VERDICT: APPROVE

Otherwise your FINAL line must be exactly:
VERDICT: REJECT

Before the final line, briefly explain the review.
