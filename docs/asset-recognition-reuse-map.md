# Asset Recognition Reuse Map

| Existing capability | Reused by this module | Location |
|---|---|---|
| Source fingerprint and index-version reuse | Cache invalidation and rebuild gating | `backend/src/main/java/com/douyin/mixcut/service/MaterialAnalysisService.java` |
| Scene cut detection | Segment boundaries | `backend/src/main/java/com/douyin/mixcut/external/FfmpegTool.java` |
| Uniform 3-second fallback | Readable segments when scene detection is unavailable | `MaterialAnalysisService.java` |
| FFmpeg thumbnail extraction | Representative and sample frame extraction | `FfmpegTool.java` |
| Application cache directory | Derived frame storage | `backend/src/main/java/com/douyin/mixcut/config/AppProps.java` |
| Existing static file mapping | Browser-readable frame URLs | `backend/src/main/java/com/douyin/mixcut/config/WebConfig.java` |
| Existing analysis API and polling | Backward-compatible result delivery | `MaterialAnalysisController.java`, `frontend/src/views/Materials.vue` |
| MyBatis stores | Segment and analysis persistence | `MaterialAnalysisStore.java`, `MaterialSegmentStore.java` |

## New data only

- `material_analysis.sample_frames_json`
- `material_segment.representative_frame_at_sec`
- `material_segment.representative_frame_url`
- `material-index-v3` cache contract

No new media library, storage service, or frontend task model was introduced.
