# Asset Recognition Architecture Map

**Module:** `asset_recognition`
**Date:** 2026-08-20
**Scope:** Scene boundaries, segment representative frames, fixed-interval sample frames, and analysis cache metadata.

```text
MaterialController / Materials.vue
        |
        v
MaterialAnalysisController
        |
        v
MaterialAnalysisService
  |-- fingerprint + material-index-v3 reuse gate
  |-- FfmpegTool.detectSceneCuts
  |-- uniform fallback segments
  |-- FfmpegTool.analysisFrame
  |-- AppProps.cache()/thumbs() frame cache
  |-- MaterialAnalysisStore
  |-- MaterialSegmentStore
        |
        +--> material_analysis.sample_frames_json
        +--> material_segment.representative_frame_at_sec
        +--> material_segment.representative_frame_url
        +--> /files/thumbs/**
```

## Data flow

1. The service computes a source fingerprint from the resolved path, size, and modification time.
2. A completed `material-index-v3` analysis with the same fingerprint is reused.
3. Scene cuts are detected with FFmpeg; uniform 3-second slices remain the fallback.
4. For videos, each segment gets a center-time representative frame.
5. Videos also receive samples at a 5-second interval, capped at 60 frames.
6. Frame names include material ID, index version, and a fingerprint-derived key.
7. Results are persisted with existing analysis/segment records and exposed through the existing analysis endpoint.
8. Spring Boot serves the cache through the existing `/files/thumbs/**` resource mapping.

## Non-goals

Planner scoring, audio policy, subtitle timelines, QC redesign, provider routing, and batch export were intentionally not changed.
