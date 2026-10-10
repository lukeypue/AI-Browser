# Related learning agents and a path to local AI

Reviewed October 10, 2026. No external source code imported in this release.

## Closest web learning project: SkillWeaver
[Official repository](https://github.com/OSU-NLP-Group/SkillWeaver), MIT licensed. Its Python/Playwright exploration loop separates agent execution, success checking, and API synthesis; it schedules exploration and testing iterations and supports repairing failed APIs. Reviewed `skillweaver/explore.py`: successful execution is followed by a success check before synthesis. This fits our goal of proposed procedure → practice → verification → reusable skill. Its runtime is not a direct Android/Kotlin/GeckoView dependency. Adapt the design, keeping our deterministic verifier and task-local values.

## Lifelong skill memory: Voyager
[Official repository](https://github.com/MineDojo/Voyager), MIT licensed. Minecraft agent with an automatic curriculum, executable skill library, feedback, and verification. Reviewed `voyager/agents/skill.py`: descriptions and code are stored as skills and retrieved using vector similarity. Useful design: retrieve a relevant proven procedure, rather than rediscovering every task. It does not require changing the foundation model's weights to accumulate skills. Minecraft actions and dependencies do not fit this app directly.

## Diagnose and measure: AgentLab / BrowserGym
[AgentLab](https://github.com/ServiceNow/AgentLab) and [BrowserGym](https://github.com/ServiceNow/BrowserGym). Provide repeatable web-agent experiments, benchmarks, and step inspection through AgentXray. Useful approach: test success, retained reuse, transfer, and wasted actions separately. A rising count of clicks is not a learning metric. Desktop benchmark tooling is best used as an external research harness, with Android fixtures for the production executor.

## Our own model without a paid provider
[llama.cpp Android documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) and [ExecuTorch Android LLM documentation](https://docs.pytorch.org/executorch/stable/llm/run-on-android.html) demonstrate local inference. An open-weight model can run on a computer we control, or a suitably provisioned phone, subject to its model license. This release does not add a local model runtime.

Procedural learning (saving verified reusable steps), a learned action selector trained on outcomes, and fine-tuning a language model are separate capabilities. The existing brain performs procedural learning, not neural weight updates. A local model can replace the remote teacher later; it will still require correct observations, pass/fail evidence, and tests of retained performance. Training a useful general language model from scratch is a much larger data/compute undertaking than adapting an existing model.

## Changes in 7.2.12
Fixed human demonstrations being written in an obsolete format that could never be selected by the current reusable-skill path. Demonstrations now enter a zero-credit training queue and become verified skills only after strict execution succeeds. Added plain-language lesson/action/pass-condition/result views. These are original changes to our existing architecture, not a claim that SkillWeaver, Voyager, or a local neural model is now integrated.

## Cloud recommendation
Keep the authenticated GeckoView session and local personal memory on the phone. A GPU server we control can host an open-weight planner and later train a task-specific adapter or action selector on deliberately collected, redacted and verified examples. Use [vLLM's serving documentation](https://docs.vllm.ai/en/latest/serving/openai_compatible_server/) to assess API compatibility with our existing client, and [torchtune](https://github.com/pytorch/torchtune) for model adaptation. GPU cost depends on model size, load, availability and uptime; [Runpod's current pricing](https://www.runpod.io/pricing) is one primary price reference, not a selected purchase. No cloud resources were provisioned.

Before choosing hardware, require: one demonstrated task retained and reused with a different value; fewer wasted actions on subsequent runs; no unrelated curriculum credit; and normal handling of expired sessions and repeat human verification. A valid signed-in session can be reused, but sites can require another challenge or login; a cloud model cannot guarantee permanent access after a single handoff.
