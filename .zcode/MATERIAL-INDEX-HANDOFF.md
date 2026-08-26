# Material Index Handoff

Date: 2026-08-19
Release: 2.2.87

## Completed

- Material analysis now records sourceFingerprint, indexVersion, attemptCount, and indexedAt.
- The current index contract is material-index-v2. A completed index is reused only when both the file fingerprint and contract version match.
- POST /api/materials/index queues only existing ready library materials. It accepts materialIds, force, and limit, caps each request at 48 materials, and never scans arbitrary machine folders.
- force=true explicitly rebuilds an unchanged material. Running work remains idempotent and does not increment the attempt count again.
- Existing OCR/transcript records are reused by the analysis service, so unchanged media does not create repeat model/API spend.
- Bootstrap migration safely adds the new analysis fields for existing MySQL databases; the fresh schema.sql includes them.
- The frontend API exposes api.batchIndexMaterials(body), and Materials.vue includes selected-material indexing plus an explicitly confirmed force rebuild action.
- A completed analysis now persists error=null; MyBatis is configured to clear stale restart/failure errors instead of returning them with a completed status.
- Application local release history is updated to 2.2.87 and strict JSON parsing reports 95 preserved history entries.

## Verification

- mvn -q -DskipTests compile passed.
- mvn -q test passed. Test output contains expected optional-runtime/network warnings only.
- npm run build passed; Vite built 1691 modules and static asset validation passed.
- Node strict JSON parse passed for data/release-history/local-release-notes.json.
- Live 8760 verification passed: bootstrap added all four material_analysis fields, POST /api/materials/index accepted a real video force rebuild, and the completed result has material-index-v2, attemptCount=2, two segments, one tag, three OCR texts, and no error.

## Next Work

- The selected-material batch index UI is complete.
- Before claiming improved final-video quality, run representative real-material evaluations: relevance of selected shots, transcript/OCR quality, audio continuity, and output QC pass rate.
- Do not claim a provider has video/vision capability unless the configured provider discovery returns it.
## Output Reliability Follow-up

- Audited real historical jobs: job 113 was correctly blocked by delivery QC after two fully duplicated timeline segments; job 111 was intentionally held because 48.666s narration for a 101s plan without BGM would create long silence; job 107 is a historical watchdog timeout during pick_audio.
- MixPlanner now stores internallyUnique and rejects duplicate footage before FFmpeg starts whenever dedupStrictness is standard or strict. Strict mode also rejects same-source overlapping intervals.
- JobService retries a different deterministic variant for duplicate-only rejections, but still fails fast when duration or required-audio constraints cannot be satisfied.
- MediaToolsService class integrity and generated-result mapping were restored, eliminating a build-blocking syntax defect and ensuring media task outputs carry materials, paths, and display data.
- Do not weaken the no-BGM/short-narration gate: it prevents known long-silence outputs.
