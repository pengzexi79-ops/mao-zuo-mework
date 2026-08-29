# GitHub Share Package: 猫作·Mework

This repository is the shareable source snapshot for Mework (猫作).

Included:

- Backend and frontend source code.
- Tests, database migrations, runtime setup scripts, and documentation.
- The D-drive launcher: `start-d.bat`.
- ZCode handoff notes, roadmap, execution plan, and safe asset-recognition snapshots.
- The application overview screenshot at `docs/assets/mework-dashboard.png`.
- The 2026-08-29 application and repository evidence screenshots at `docs/assets/*-20260829.png`.
- A sanitized index of the 67-commit local development history at `docs/zcode-handoff/GIT_HISTORY.md`.
- The factual 2026-08-28 to 2026-08-29 delivery record at `docs/zcode-handoff/DEVELOPMENT_LOG_20260828_20260829.md`.
- The latest existing installer is attached to the GitHub Release marked `legacy-2.2.87`.

Excluded intentionally:

- `.env` files, API keys, passwords, and machine credentials.
- Local databases, generated media, logs, caches, virtual environments, models, and portable runtimes.
- Raw ZCode monitor state and activity logs, which contain machine telemetry and are not suitable for a shareable repository.
- The current `2.2.150` Setup EXE, because a complete installer for that version has not been produced yet.

To run from a fresh Windows checkout, install or provide the runtime dependencies,
copy `.env.example` to `.env`, configure the local database and provider keys, then
run `start-d.bat`. The application stores its default data and logs under the
checkout's `data` directory and uses the bundled D-drive tools when present.
