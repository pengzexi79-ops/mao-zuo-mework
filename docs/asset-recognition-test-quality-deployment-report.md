# Asset Recognition Test, Quality, and Deployment Report

**Date:** 2026-08-20

## Automated tests

- `mvn -q -DskipTests compile` — passed.
- `mvn -q -Dtest=MaterialAnalysisServiceTest test` — passed.
- `mvn -q -Dtest=MaterialAnalysisServiceTest,FfmpegToolTest test` — passed.
- `mvn -q test` — passed twice, including after final migration cleanup.
- `npm run build` — passed; text-integrity, Vite production build, and static reference verification passed.
- `mvn -q package -DskipTests -Ddelivery.jar.name=mixcut-next` — passed.
- JAR inspection confirmed Spring Boot launcher, bundled static index, and material analysis migration resource.

Expected test-suite warnings remained visible for unavailable test-only Python paths, blocked external network calls, and mocked null database connections. They did not fail tests and were not hidden.

## Real material validation

- Health: `GET /api/system/env` returned HTTP 200 with database, FFmpeg, FFprobe, and media runtime ready.
- Material library: 191 records; ready videos were available.
- Validation material: ID `569`, duration about 31.23 seconds.
- `POST /api/materials/569/analyze` entered `running` and completed within the 3-minute bound.
- Result: 11 persisted segments, 7 fixed-interval samples, 18 generated JPG cache files.
- Segment 0 representative frame: 1.5 seconds, `/files/thumbs/a569-61c0385a2438daf2-s0.jpg`.
- Representative frame HTTP validation: 200, `image/jpeg`, 20,710 bytes.
- Final deployed readback after restart: `completed`, 11 segments, 7 samples, HTTP 200 frame, `material-index-v3`.

## Quality boundaries

- Original media files were not modified.
- Frame extraction failure is recorded as a structured analysis issue; it does not silently abort all analysis.
- Sample generation is bounded to 60 frames per video.
- Cache files are keyed by material ID, source fingerprint, and index version.
- Old cache files for the same material are removed only from the application-managed thumbs directory.
- Existing API keys and provider capabilities were not changed or exposed.

## Deployment

- Built `backend/target/mixcut-next.jar`.
- Verified the process on port 8760 belonged to this project before stopping it.
- Preserved timestamped pre-deployment JAR backups.
- Replaced `mixcut-current.jar` and restarted with the existing `start.bat` flow.
- Final health check: HTTP 200 at `http://127.0.0.1:8760/api/system/env`.
- Application URL: `http://127.0.0.1:8760`.
