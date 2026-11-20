# Persistent planner authentication hold

Repeated physical-phone HTTP401 responses prevented AI repair while saved local procedures still loaded. The goal is to suppress repeated rejected requests without changing credentials or website learning. The exact provider authentication cause remains outside this change.

## Chosen approach
Wrap the existing planner client with a content-free durable connection hold. A first HTTP401 records rejection; subsequent clients/tasks/restarts do not send AI requests or reserve teacher allowance. Local execution continues unchanged. A deliberate successful SAVE in the existing AI planner settings creates a fresh local generation, allowing another attempt. Ordinary test/service restart, master toggles, request-cap bypass and elapsed time cannot reset it. An older in-flight failure cannot block a newer saved generation.

Rejected alternatives: repeated timed401 retries continue the observed waste; silently switching provider or credentials invents access and risks unexpected costs. Repairing the actual authentication still needs the owner's secure settings action.

## Storage/UI/City
Use a dedicated app-private connection-state directory separate from learned site memory and synthetic backups. Store schema, random local generation and a boolean only, never keys/URLs/models/bodies. Invalid/unreadable state fails closed; write failure retains an in-process hold. Display a clear rejected/repair-needed message on home and learning screens. Include the held cause in existing allowlisted test-start/end planner_failure metadata without counting it as a new request failure. Extend City's existing authentication diagnostic case to check no repeat transport after durable reload, without changing report schemas, exam scores or synthetic memory import.

## Gates
Test-first core and app persistence/wiring; first failure vs subsequent no-request accounting; fresh-generation retry; stale failure; non401 unchanged; corrupt/read/write failures; local-only budget controls. Run full core/app, runner, receiver/publisher, browser and pinned fixture checks, Android build/lint, actual City preflight and independent review. Preserve signing/workflows/versions, timers/uploader and single review. Serialize stable publication after pending prior server promotion is verified, then use existing stable signed release controller. No phone install or Claude request.
