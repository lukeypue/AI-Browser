# Site Brain research and evaluation — 2026-09-29

## Decision

Improve verified local procedures, targeted practice and bounded recovery before adding another model. A known procedure should execute without inference; unfamiliar or failed procedures may request an optional teacher. This is an engineering choice supported by the evidence below, not a claim that any model or app understands every website.

**No Claude or Gemini model was contacted for advice, and no provider quality benchmark or paid inference call was performed.** Research used official documentation and papers; implementation tests used local fixtures and transports. The separate external-review document contains prompts to run later.

## What the supplied diagnostics establish

The latest export, `ai-browser-diagnostics-1790704705.ndjson`, has 6,905 records from **2026-09-29 13:19:53–17:58:20 UTC**, a 4h38m27s window. The earlier `1790704694` export's 6,901 records are an exact prefix, not another experiment.

| Finished-task measure | Observed |
|---|---:|
| Learning tasks | 572 |
| Newly verified lessons | **0** |
| Ledger-counted model calls | 522, approximately 112.5/hour |
| Tasks using a model | 262 |
| DONE / PARTIAL / FAILED / BUDGET_EXHAUSTED | 197 / 192 / 182 / 1 |
| Tasks stopped by remembered-failure cooldown | 244 |

Of 2,145 action outcomes, 831 verified. Planner outcomes verified **98/704 (13.9%)**. Separately identified compiled-skill steps verified **141/219 (64.4%)**. These populations differ: compiled procedures are selected from previously successful cases, so the rates do not prove that one policy or model is intrinsically better.

There were **197 next_page lesson tasks without a PAGE_NEXT/next_page action outcome**. This supports stopping repeated irrelevant practice. It does not prove the sites can never paginate: extraction, page context and alternative controls remain possible explanations.

The old ledger counts are not exact billable requests: one client call could secretly try several models. Tokens, cache use, provider identity and actual billed dollars were absent. The new request allowance therefore cannot be described as a measured percentage reduction in the historical bill.

## Research translated into implementation

[Agent Workflow Memory](https://arxiv.org/abs/2409.07429) supports inducing and retrieving reusable workflows. [SkillWeaver](https://arxiv.org/html/2504.07079v1) supports discovering, rehearsing and validating skills. Their published results are not Site Brain results. [Stagehand](https://docs.stagehand.dev/v3/best-practices/deterministic-agent) documents cached deterministic workflows with repair; [Playwright locators](https://playwright.dev/docs/locators) motivate fresh target resolution. None makes clicking equivalent to task completion.

The selected contracts, defined in [the design](../superpowers/specs/2026-09-29-local-first-brain-design.md), are:

- Observe opportunities each minute; defer absent or repeatedly unproductive lessons without claiming mastery. Practice available canonical facets and stop after the requested outcome. Synthetic detail practice must not buy unrelated evidence extraction.
- Compile verified parameterized procedures with host-independent identity and separate host statistics. Prefer local evidence. Transfer requires source evidence, compatible entry/parameters and no target failure; a transfer must earn fresh verification.
- Try bounded local recipes before the teacher. Re-ground each retry on current controls and current effect classes. Reject ambiguity, unsafe actions and invented selectors.
- Reserve **24 actual teacher requests per rolling 24 hours, at most 6 per rolling hour**, per installation across tasks and restarts. Failed requests consume allowance. This is neither a dollar cap nor a shared quota across users/devices. Developer-funded fleet billing requires server-side quotas. Local-only execution sends no teacher requests and has zero API cost; website success is not guaranteed.
- Preserve explicit provider/model choices, remove automatic model fallback, record reported tokens, reject redirects and keep response bodies out of errors. Missing usage remains unknown.

Authentication, CAPTCHA, 2FA, payment and destructive/account-changing boundaries remain enforced by the existing executor and network protections. Ordinary page data and learned records remain local; model requests use the redacted structured interface.

## Synthetic evaluation: useful evidence with a narrow scope

`LocalFirstEvaluationTest` uses the real parser, engine and verifier with a synthetic renderer. “Held-out” here means changed host/control IDs within **the same FakeSite semantics**, not an unseen production website. A supplied abstract search program is rehearsed three times through verification; no source success counts are manually seeded and no teacher API creates that program.

The recorded baseline and updated JSON snapshots show:

| Scenario | Baseline actions / teacher-spy requests | Updated actions / requests | Updated outcome |
|---|---:|---:|---|
| Available search | 4 / 1 | 1 / 0 | Verified target |
| Available open item | 3 / 1 | 1 / 0 | Verified target |
| Available next page | 4 / 1 | 1 / 0 | Verified target |
| Absent next page | 1 / 0 | 0 / 0 | PARTIAL; no false completion |
| Compatible transfer | 2 / 0 | 1 / 0 | Learned procedure actually verified |
| Incompatible entry | 2 / 2 | 0 / 0 | PARTIAL; transfer rejected |
| Changed-control recovery | 2 / 1 | 2 / 0 | Fresh replacement verified |

Across these seven cases: **18→6 actions and 6→0 teacher-spy requests**. Do not turn this into a general live-site success rate: the old compatible case used a builtin rather than transferring the compiled procedure, and old DONE included the absent target. The frozen final source passed all **186 core JVM tests**, including the four evaluation methods; all 88 source/test input hashes stayed unchanged through compilation and testing. Six Android policy unit tests and three document-isolation tests passed locally. Browser perception, the full Android build and signing checks are separate CI release gates. Neither these fixtures nor elapsed JVM timings establish phone latency, battery behavior or provider quality.

## Provider choices and cost

Prices checked against official documentation on 2026-09-29: USD per million **uncached input / billed output** tokens, standard synchronous inference. Reasoning can add billed output. Account eligibility, quotas and prices can change.

| Option | Input / output | Selection |
|---|---:|---|
| [GPT-5.4 mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini) | $0.75 / $4.50 | Blank OpenAI model defaults to `gpt-5.4-mini-2026-03-17` |
| [GPT-5.4 nano](https://developers.openai.com/api/docs/models/gpt-5.4-nano) | $0.20 / $1.25 | Cheaper explicit candidate; qualify on the same tasks |
| [GPT-5 nano](https://developers.openai.com/api/docs/models/gpt-5-nano) | $0.05 / $0.40 | Narrow classification/extraction candidate |
| [Groq GPT-OSS 20B / 120B](https://console.groq.com/docs/models) | $0.075 / $0.30; $0.15 / $0.60 | Optional Groq key; default `openai/gpt-oss-20b` |
| [Gemini 3.5 / 3.1 Flash-Lite](https://ai.google.dev/gemini-api/docs/pricing) | $0.30 / $2.50; $0.25 / $1.50 | Optional Gemini key; default `gemini-3.5-flash-lite` |
| [Claude Haiku 4.5](https://platform.claude.com/docs/en/about-claude/pricing) | $1 / $5 | Anthropic default `claude-haiku-4-5-20251001` |

OpenAI uses Responses with strict JSON; compatible/Groq/Gemini use Chat Completions. Gemini's [compatibility endpoint](https://ai.google.dev/gemini-api/docs/openai) is documented. Only the pinned OpenAI default receives automatic `none` reasoning; older custom models receive no unconfigured effort. Anthropic Messages support remains. No key purchase is required to use local procedures.

[Groq's published free GPT-OSS limits](https://console.groq.com/docs/rate-limits) are 30 RPM, 1,000 RPD, 8,000 TPM and 200,000 TPD, subject to account exceptions. [Strict JSON](https://console.groq.com/docs/structured-outputs) cannot currently combine streaming/tool use; the app requests a JSON proposal. [Retention controls](https://console.groq.com/docs/your-data) permit ZDR for all customers; reliability/abuse exceptions otherwise allow temporary logging.

[Gemini quotas](https://ai.google.dev/gemini-api/docs/rate-limits) are project/account dependent. [Free-service terms](https://ai.google.dev/gemini-api/terms) generally allow product improvement/human review and prohibit submitting sensitive, confidential or personal information; EEA/UK/Swiss exceptions apply. Paid terms exclude product improvement but retain safety logging. [Gemini 2.5 access](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-lite) is restricted to prior active users, so its cheaper price is not promised to new accounts.

[OpenAI](https://developers.openai.com/api/docs/guides/your-data) and [Anthropic](https://privacy.claude.com/en/articles/7996868-is-my-data-used-for-model-training) exclude API training by default; retention and optional controls still matter. `store:false` is not OpenAI ZDR. OpenAI small-model pages show no free API tier.

[DeepSeek Flash](https://api-docs.deepseek.com/quick_start/pricing/) is $0.15/$0.60 off-peak and $0.30/$1.20 peak, but no renewable free quota or API-specific no-training guarantee was verified. [Cerebras](https://inference-docs.cerebras.ai/support/rate-limits) currently offers a $5, 30-day trial requiring a payment method, not permanent free service. [OpenRouter's free router](https://openrouter.ai/openrouter/free) can change selected models; explicit routing is preferable during qualification.

Choose by cost per **verified outcome**, including retries and latency, not token price alone. Example only: 1,000 mini calls at 4,000 input plus 600 total billed output tokens cost $5.70 before other charges; this is not observed app spending.

## Local-model roadmap

No model weights are bundled. Procedural learning stores verified programs and statistics; it does **not** fine-tune foundation-model weights or automatically confer cross-project expertise.

Evaluate an optional [LiteRT-LM Android](https://developers.google.com/edge/litert-lm/android) or [llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) adapter behind `PlannerClient`. Start with a small text model, constrained proposals and the same verifier. [ML Kit GenAI](https://developers.google.com/ml-kit/genai) foreground/device restrictions make it unsuitable as an assumed unattended-service dependency.

Before shipping, measure schema validity, verified outcomes, RAM with Gecko, cold-load/p95 latency, cancellation, battery and heat on the actual phone. A larger download does not guarantee capability or enough runtime memory. Later fine-tuning requires separately validated examples, licenses and evaluation; zero inference cost for known procedures is already possible through local replay.
