# Mework Project Handoff

## 1. Product Position

Mework is a local-first short-video production application for operators who need to import authorized material, plan a fixed sequence, generate and quality-check outputs, then edit and export them. The main production path is:

1. Create a project and import or fetch permitted material.
2. Review material metadata, quality and role classification.
3. Choose material scope, a fixed ordering rule and production settings in Studio.
4. Dry-run, prepare and render jobs; inspect quality control results.
5. Open outputs in the editor, apply a reviewed edit candidate, then download or manage outputs.

The application also offers AI copy assistance, provider-managed AI image/video/voice generation, audio/video utilities, authorized-source fetching, workflow/Skill configuration, and local environment/capability status.

It is not a hosted media platform, a payment proxy, an account-login automation tool, a DRM bypass, or a general remote-code execution environment.

## 2. Current Runtime And Build

- Product release record: `2.2.100`, dated `2026-08-20`; release metadata lives in `backend/src/main/resources/release-notes.json` and `data/release-history/local-release-notes.json`.
- Maven coordinate: `com.douyin:mixcut:1.0.0`; the product release version and Maven version are intentionally separate.
- Frontend: Vue 3, Vue Router, Element Plus, Vite. `frontend/package.json` defines `npm run build`, which verifies text integrity, builds Vite assets and validates the static bundle.
- Backend: Spring Boot 3.3.2, Java 17, MySQL, JPA/MyBatis. Default bind address is `127.0.0.1`, port is `8760`.
- Static-bundle flow: `frontend/vite.config.js` writes Vite output into `backend/src/main/resources/static`; Maven packages that static directory into the executable JAR.
- Normal launcher: `start.bat`. It loads the local `.env` without printing its values, verifies Java and the selected JAR, checks the bundled UI, and starts or health-checks the instance.
- Normal runtime artifact selection: `backend/target/mixcut-current.jar`, then `mixcut-delivery.jar`, then `mixcut.jar`. Do not assume the newest filename by timestamp is the normal launch artifact; many historical JARs are intentionally retained.
- A primary repository `.git` directory is absent. Treat `release-notes.json`, `data/release-history`, JAR timestamps, tests and runtime checks as the available change evidence.

## 3. Frontend Map

- `frontend/src/router.js`: client routes. Main pages: Dashboard, Materials, Media Tools, Crawl, AI Settings, AI Create, Projects, Workflows, Fixed Order Presets, Tutorial, Studio, Outputs, Editor, Resource Center and Capabilities.
- `frontend/src/App.vue`: application chrome, navigation, environment dialog and release controls.
- `frontend/src/views/Studio.vue`: production workbench, material scope, preflight, fixed-order settings, render submission and job status.
- `frontend/src/views/Materials.vue`, `Crawl.vue`, `MediaTools.vue`: material ingestion, authorized fetching and media utilities.
- `frontend/src/views/AiSettings.vue`: provider definitions, model discovery candidates and explicit adoption of executable media models.
- `frontend/src/views/AiCreate.vue`: AI image/video/voice submission and task polling. It only displays providers/models confirmed by the backend.
- `frontend/src/views/Outputs.vue` and `Editor.vue`: output library, QC, edit candidate review and apply flows.
- `frontend/src/api.js`: shared API client. Preserve its AI generation, task, provider and output APIs unless the corresponding backend contract is changed deliberately.

## 4. Backend Map

- `web/MaterialController`, `MaterialAnalysisController`, `MaterialGapController`: material import, metadata/quality analysis and gap diagnosis.
- `web/CrawlController`: permitted-source fetching and crawl-job control.
- `web/ProjectController`, `WorkflowController`, `JobController`: projects, workflow/Skill execution, dry-run/preparation/render and output lifecycle.
- `web/MediaGenerationController` plus `service/MediaGenerationService` and `MediaProviderCatalog`: AI media provider discovery, confirmed-provider lists, image/video/voice task submission and polling.
- `web/MediaToolsController` plus `service/MediaToolsService` and `AudioEngineService`: image/audio/video processing and task state.
- `web/AiController` plus `service/AiService`: provider configuration, copy/chat routes and model discovery/adoption.
- `web/SystemController` and `LocalConfigController`: `/api/system/env`, capabilities, release notes, local setup state and guarded local configuration actions.
- `service/RenderPreparationService`, `RenderService`, `DeliveryQcService`, `DeliveryRepairService`, `OutputEditorService`: preparation, rendering, quality gates, repair and output editing.
- `security/UrlGuard` and `external/CrawlerGateway`: outbound URL validation and restricted media retrieval.

Important stable endpoints include `/api/system/env`, `/api/ai-generation/providers`, `/api/ai-generation/tasks`, `/api/jobs/outputs/all`, `/api/jobs/*`, `/api/materials/*`, `/api/crawl/*` and `/api/workflows/ai-comic`. The last endpoint remains in place even though the former canvas UI is gone; do not remove it without confirming other callers and product plans.

## 5. Functional Boundaries

Capability status is honest by design:

- A bundled capability is only available when its executable and prerequisites are present.
- A repairable capability may have a setup path but is not automatically installed or treated as active.
- AI providers require a user-supplied account/API key and explicit model adoption before submission becomes available.
- Official-resource links may only open the official destination; they do not imply the resource is integrated or licensed.
- Local FFmpeg/FFprobe, optional Python tools, offline ASR data and hardware/model needs must be surfaced as environment requirements.

## 6. Security And Compliance

- Keep API keys only in local server-side encrypted storage or local environment configuration. Do not add them to source, release notes, browser bundles, tests, logs or this document.
- External URLs must be `http`/`https` and pass `UrlGuard`; loopback, private, reserved and unsafe redirected addresses are rejected.
- Do not read browser cookies or passwords. Do not automate third-party logins, payment, region restriction, DRM or content-review bypasses.
- The default service binds to loopback. A non-loopback bind requires `APP_ACCESS_TOKEN`; local configuration/restart actions stay PC-local.
- Custom Skill definitions are constrained DSL, not shell/code templates. Keep the forbidden command, URL, HTTP, download, script and process fields forbidden.

## 7. Recent History

The release record carries the detailed history. Recent entries are `2.2.80` through `2.2.100`; inspect the history array in `backend/src/main/resources/release-notes.json` or the locally extended `data/release-history/local-release-notes.json` before adding a new record. The currently declared release is `2.2.100`, focused on Studio and output-library layout stability.

Historical JAR names may include previous experimental or fix labels. They are evidence only, not a declaration that the named feature should be restored.

## 8. This Handoff Change: Infinite Canvas Removal

Completed in the source tree:

- Removed `frontend/src/views/InfiniteCanvas.vue`.
- Removed its navigation item from `App.vue` and its unused `Grid` icon import.
- Replaced the former route with a compatibility redirect: `#/infinite-canvas` now goes to `#/ai-create`; it does not lazy-load a deleted component.
- Removed Studio's canvas shortcut, its dual-mode launcher dialog/state/branch, and retained AI Create as a direct shortcut.
- Removed the obsolete canvas-to-AI-create localStorage draft handoff.
- Updated tutorial and AI-provider copy so it no longer advertises the removed interface.

Deliberately preserved:

- AI image/video/voice provider, submission and task APIs.
- AI Create page and its material-library completion behavior.
- Media tools, material library, outputs/editor, render jobs and backend AI-comic workflow endpoint.
- Existing media/output records. No material, task, output or user data was deleted.

The old canvas localStorage keys are no longer read. No broad browser-storage cleanup was introduced, avoiding accidental deletion of unrelated local user data.

## 9. Verification And Packaging

From the project root:

```powershell
Set-Location '<legacy-workspace>\ai-douyin-mixcut\frontend'
npm run build

Set-Location '..\backend'
mvn -q test
```

The frontend build rewrites `backend/src/main/resources/static`; never hand-edit hashed assets or `index.html` to remove a chunk. After a successful build, ensure no `InfiniteCanvas-*` asset is referenced by the regenerated static bundle.

Package only after the frontend build succeeds:

```powershell
Set-Location '<legacy-workspace>\ai-douyin-mixcut\backend'
mvn -q package
```

When updating `mixcut-current.jar`, back up the old normal artifact before replacing it. Do not overwrite historical JARs. Start an isolated instance only on a free port and separate data directory, then validate:

```powershell
Invoke-RestMethod http://127.0.0.1:8760/api/system/env
Invoke-RestMethod http://127.0.0.1:8760/api/ai-generation/providers
Invoke-RestMethod http://127.0.0.1:8760/api/jobs/outputs/all
```

Also open `#/ai-create`, `#/studio` and `#/outputs`; confirm `#/infinite-canvas` redirects to `#/ai-create` and never requests a canvas chunk.

## 10. Risks And Next Work

- The static bundle currently in the source tree may have assets from prior builds until `npm run build` completes. Do not report the removal as packaged until that build and JAR packaging are complete.
- Because there is no root Git repository, create a new release-note record with focused evidence after validation instead of relying on commit history.
- Preserve the backend `ai-comic` route until an explicit deprecation decision identifies all callers.
- Before a production replacement, verify the running process uses the intended `mixcut-current.jar` and not a legacy JAR on the same port.

Suggested first actions for the next development task:

1. Read this file, the current release record, `frontend/src/router.js`, `App.vue`, `views/Studio.vue`, `views/AiCreate.vue`, `backend/src/main/resources/application.yml`, and the relevant controller/service pair.
2. Run the frontend build and backend test suite before editing unrelated features.
3. Start an isolated instance with an explicit unused port/data directory when validating behavior that could affect existing records.
4. Verify `/api/system/env` first, then the feature-specific API; do not mix an old JAR with freshly generated static assets.
5. Add a continuous patch release record only after both the code and the stated verification evidence exist.
