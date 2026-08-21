# Asset Recognition Module Change Note

**Date:** 2026-08-20
**Baseline:** `D:\zcode\archives\ai-douyin-mixcut-asset-recognition-baseline-20260820.zip`
**Baseline SHA-256:** recorded in the adjacent `.sha256` file.
**Rollback:** `backend/target/mixcut-current-before-final-*.jar` (the timestamped JAR created immediately before final deployment); source baseline ZIP above.

## Allowed files

- `MaterialAnalysis.java`
- `MaterialSegment.java`
- `MaterialAnalysisService.java`
- `FfmpegTool.java`
- `BootstrapService.java`
- `db/material-analysis-migration.sql`
- `db/schema.sql`
- `MaterialAnalysisServiceTest.java`
- `frontend/src/views/Materials.vue`
- this module's documentation files

## Changes

- Added representative-frame metadata to persisted segments.
- Added fixed-interval sample-frame JSON to analysis records.
- Added fingerprint/version-aware frame cache names and cleanup of stale derived frames.
- Added `analysisFrame` as a constrained wrapper around the existing FFmpeg thumbnail operation.
- Upgraded the analysis contract to `material-index-v3` so old completed indexes rebuild once.
- Kept image/audio segment persistence unchanged; only videos produce frame files.
- Added the representative-frame column to the existing Materials analysis table without introducing a new UI workflow.
- Added fixed-field startup migration checks for existing MySQL installations.

## Deliberately unchanged

Planner, render command generation, audio, subtitles, QC, repair, provider routing, credentials, and unrelated projects.
