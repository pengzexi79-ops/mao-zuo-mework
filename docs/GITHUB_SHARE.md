# GitHub Share Package

This repository is the shareable source snapshot for Mework (猫作).

Included:

- Backend and frontend source code.
- Tests, database migrations, runtime setup scripts, and documentation.
- The D-drive launcher: `start-d.bat`.
- ZCode handoff notes, roadmap, execution plan, and safe asset-recognition snapshots.
- The latest existing installer is attached to the GitHub Release marked `legacy-2.2.87`.

Excluded intentionally:

- `.env` files, API keys, passwords, and machine credentials.
- Local databases, generated media, logs, caches, virtual environments, models, and portable runtimes.
- The current `2.2.150` Setup EXE, because a complete installer for that version has not been produced yet.

To run from a fresh Windows checkout, install or provide the runtime dependencies,
copy `.env.example` to `.env`, configure the local database and provider keys, then
run `start-d.bat`. The application stores its default data and logs under the
checkout's `data` directory and uses the bundled D-drive tools when present.
