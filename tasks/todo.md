# Todo

## 2026-07-19 AI UI Startup

- [x] Reproduce the backend startup failure and capture the complete root-cause chain.
- [x] Verify the existing frontend/backend AI UI-generation contract and make only evidence-backed fixes.
- [x] Run focused frontend tests/build and the required backend Maven verification.
- [x] Start both services on 9527/9528 and complete a browser smoke test of the AI designer flow.

## 2026-07-19 Review

- Confirmed the reported Spring condition output was not the startup cause; a healthy Java process already owned port 9527, so the second backend launch failed with a port conflict.
- Verified enabled LLM configurations, local sign login, Agent skill loading, topic persistence, and a real SSE completion that returned a complete Vue SFC and terminal frame.
- Started the VTJ.PRO frontend on port 9528 and verified in the browser that the AI panel loads without a login warning, exposes the prompt control, and carries the selected h1 node id.
- Fixed classic `function () {}` event handlers being misclassified as assignment snippets in the frontend renderer; browser reload error increments changed from `[1, 1]` to `[0, 0]`, and the affected login event opens and closes its dialog.
- Verified frontend Designer 83 tests, Renderer 198 tests, Local/Renderer/Designer production builds, backend compile, backend 50 tests, and backend `clean package`.
- Both services remain listening at `http://localhost:9527` and `http://localhost:9528`.

- [x] Strengthen the VTJ Agent capability contract and context budgeting so page, component, DSL, API, event, and JavaScript tools remain executable when context is large.
- [x] Add focused regression coverage for capability guidance and oversized context retention.
- [x] Run the required Maven test and package verification.

## Agent Capability Review

- Added deterministic routing guidance for page, block/component, DSL/Vue, API, event, JavaScript, global configuration, and runtime verification tools.
- Preserved registered tool signatures and current Vue source under oversized prompt contexts through priority budgets and tool-catalog compaction.
- Added VTJ-compatible Options API round-tripping for reactive state, methods, computed values, and template interpolations so generated functionality survives Vue-to-DSL conversion and staged diffs.
- Documented the create/activate/generate/apply/refresh workflow used by the frontend's existing SSE and auto-apply loop.
- Removed the duplicate legacy exception advice that prevented Spring Boot from starting, and restored the pre-existing project-search endpoint's missing service implementation.
- Verified 47 tests, clean package creation, live startup on port 9527, `/api/health`, `/api/open/skills/web`, and interactive `parseVue` output.
- Residual boundary: arbitrary JavaScript syntax is not parsed as a full AST by the Java fallback; Agent output is constrained to VTJ's `defineComponent` + `reactive` + `methods` + `computed` shape.

- [x] Draft README for running vtj-server with VTJ.PRO frontend source.
- [x] Re-run Maven tests/package after documentation update.
- [x] Stage only intended backend/docs files, commit, and push to GitHub.

## Review

- Added README instructions for running vtj-server with the VTJ.PRO frontend source, including backend env vars, frontend proxy setup, AI model config, and troubleshooting.
- Tightened the database password default to rely on `VTJ_DB_PASSWORD` instead of committing a concrete default value.
- Verified with `mvn -s maven-settings.xml test` and `mvn -s maven-settings.xml clean package`.
- Committed and pushed branch `codex/vtj-frontend-backend-readme-ai`, then created GitHub PR #1.

## Previous AI Agent Work

- [x] Add a minimal Vue SFC to VTJ DSL parser so AI output creates visible nodes.
- [x] Serialize current VTJ DSL back to Vue well enough for staged diff updates.
- [x] Update Agent prompts to prefer layout-first, section-by-section generation and preserve follow-up context.
- [x] Add focused unit tests for parser and staged prompt behavior.
- [x] Run backend tests/package and verify the in-browser AI apply flow.

## Previous Review

- Replaced the empty `parseVue` stub with a minimal Vue SFC to VTJ DSL converter that preserves visible HTML nodes, text, common props, simple events/directives, and scoped CSS.
- Added DSL to Vue serialization for current-page source so staged diff updates have usable context.
- Updated Agent orchestration and skill docs to prefer layout skeleton first, then one section/component per turn, while preserving previous objective context after `O:` tool results.
- Verified backend tests/package and rendered a saved homepage skeleton in the VTJ designer canvas.

## Selected Component Agent Scope

- [x] Add a VTJ selected-component design skill with valid NodeSchema, JSExpression, JSFunction, event, directive, and child-node rules.
- [x] Include the captured frontend selection in Agent prompts and make it an immutable modification boundary.
- [x] Echo selection context to frontend chat payloads for scoped application and follow-up turns.
- [x] Add regression tests and run the required Maven verification.

## Selected Component Review

- `/api/open/skills/{platform}` now exposes `selected-component` and the `vtj-node` response protocol.
- Agent prompts require a complete NodeSchema and forbid page/global mutations when selection context is present.
- Verified 50 tests, `mvn -s maven-settings.xml clean package`, and the live skills endpoint on port 9527.
