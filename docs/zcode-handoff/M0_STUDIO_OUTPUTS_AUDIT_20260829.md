# M0: Studio / Outputs audit

Date: 2026-08-29

## Scope

The live D-drive instance is `<release-workspace>\ai-douyin-mixcut` on port `8762`. The C-drive process on port `8760` is an older instance and was not changed. The pasted handoff path points at that older deployment, so this audit follows the source that serves the live D-drive application.

## Reuse map

| Product concern | Existing implementation | Decision |
| --- | --- | --- |
| Project and workflow parameters | `frontend/src/views/Studio.vue`, `ProjectController`, `WorkflowController` | Reuse |
| Material filtering, quality admission and gap analysis | `MaterialAnalysisService`, `MaterialGapService`, `RenderAdmissionService` | Reuse |
| Dry-run plan and preflight | `Studio.vue` -> `JobController` / planner services | Reuse as the batch gate |
| Deterministic rendering and output naming | `RenderService`, `JobService` | Reuse |
| Single-output timeline editing | `frontend/src/views/Editor.vue`, `OutputEditorService` | Keep as the real editing surface |
| Structured QC and repair decisions | `DeliveryRepairService`, `JobOutput.qcJson`, Outputs repair dialog | Reuse |
| Delivery asset browsing | `frontend/src/views/Outputs.vue`, `JobOutput` | Improve selection/filter safety first |

## First-principles findings

1. A preview plan is not an editable timeline contract. Studio currently produces a read-only dry-run plan; Editor owns a persisted edit session. A new Studio timeline must not pretend to affect final rendering until a backend contract exists.
2. A delivery action must operate on the visible, user-selected set. The old Outputs selection logic compared the global selection length with the filtered row count, and clearing the current filter could clear selections outside that filter.
3. A missing output file is a delivery state, not a downloadable asset. Batch download now excludes diagnostic-only or missing-file rows and reports the actual count.
4. Refresh is allowed to reconcile selection with server truth. Stale IDs are removed after output reload so later batch actions cannot target records that are gone.

## M9 slice completed in this turn

- Added delivery-status and filename/task search filters.
- Made select-all additive/removable only within the current filtered view.
- Preserved selections when changing filters.
- Pruned stale selections after reload.
- Counted only existing files for batch download.
- Reported partial batch-delete failures instead of claiming full success.
- Archived the pre-change Outputs source under `docs/zcode-handoff/archives/20260829-outputs-before-m9`.

## Not marked as connected

- No unverified OCR, ASR, TTS, visual model or arbitrary command execution was added.
- No new multi-track schema was introduced without a renderer/editor persistence contract.
- No source materials or output files were deleted during this module.

## Rollback point

Restore `docs/zcode-handoff/archives/20260829-outputs-before-m9/Outputs.vue` to `frontend/src/views/Outputs.vue` if the Outputs module must be reverted.

## Next module

Audit and harden Studio's batch-submit state machine around dry-run invalidation, preparation cancellation, and job refresh. Keep the existing Render/QC path and add tests before any new timeline contract.
