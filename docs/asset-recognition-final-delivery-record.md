# Asset Recognition Final Delivery Record

**Delivery date:** 2026-08-20
**Module:** `asset_recognition`
**Status:** Delivered and running locally.

## Delivered behavior

- Scene/fallback segmentation remains deterministic and backward compatible.
- Video segments now expose representative frame time and URL.
- Video analysis now exposes fixed-interval samples at 5 seconds, capped at 60.
- Frame cache keys include material ID, source fingerprint, and `material-index-v3`.
- Existing analysis results are rebuilt once after the index-version upgrade.
- The existing Materials analysis dialog displays representative frames.
- Image/audio segments remain persisted without unnecessary frame extraction.

## Verification seal

- Backend full test suite: passed.
- Frontend production build and static verification: passed.
- Spring Boot package inspection: passed.
- `/api/system/env`: HTTP 200 after final restart.
- Real material `569`: completed; 11 segments, 7 samples.
- Representative frame URL: HTTP 200, JPEG, 20,710 bytes.
- Final readback index version: `material-index-v3`.

## Archive and rollback

- Passing source baseline: `D:\zcode\archives\ai-douyin-mixcut-asset-recognition-baseline-20260820.zip`.
- Final deployment rollback artifact: timestamped `backend/target/mixcut-current-before-eof-fix-*.jar`.
- The current process uses `backend/target/mixcut-current.jar` and is served at `http://127.0.0.1:8760`.

## Human decision points carried forward

Planner semantic scoring, audio conflict policy, subtitle timeline ownership, QC evidence policy, and final delivery strategy remain unchanged and require the previously documented human confirmation.
