# Practice City challenge implementation plan

Goal: make the synthetic city a more useful regression detector after the old city reached100%, without claiming real-site mastery.

Design: retain exact fixed/unseen24-case and144-case city manifests and existing scorer. Add an opt-in frozen432-case challenge (24 districts,9 levels,2 pagination modes), with positive exam seeds and never-trained challenge hosts. Cover larger catalogs, page sizes1/2/4/8, all four queries, one/two-step search, missing mileage/rare evidence, stale/loading/no-op/blank/error/auth and explicit CAPTCHA. Training stays3600 negative-seed search lessons. Each evaluation receives a separate frozen-memory fork; no phone memory import or model calls. Auth/CAPTCHA must stop at NEED_HUMAN with zero actions. Report all results honestly, including failure.

Implementation:
- Add contract regressions for challenge determinism, frozen legacy digests, fault/query/catalog coverage, and CAPTCHA boundary.
- Add challenge generator and opt-in pre/post summaries, paired case manifests and district breakdown; add only the CAPTCHA environment/scorer support required.
- Independently validate and allowlist optional challenge report fields in runner/exporter; reject incomplete/unsafe manifests and duplicate cases, preserve all old exports and phone records.
- Run fullcore/runner/export/browser checks, independent review and a complete3600-episode offline session under the existing limits. No appversion/signing/workflow edits or apprelease needed.
- Push an isolated feature branch; deploy immutable verified lab revision and uploader code only while idle under writer/training locks. Preserve existing timer/serviceunit bytes and reports. Confirm one enlarged report is retrievable through the connector.

Resource limits: unchanged1800-second run timeout,3GiB memory,2CPUquota,48 bounded rawarchives. Rollback: retain currentsource revision and exporterbackup. Existing safety/truthfulness gates never loosened. Synthetic training does not update foundation-model weights or Android memory.
