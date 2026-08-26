# 猫作·Mework ZCode Handoff Archive

This directory is the D-drive handoff archive for the Mework project.

## Source locations

- Project source and Git history: `D:\zcode\projects\ai-douyin-mixcut`
- Original ZCode project: `C:\Users\Windows\WorkBuddy\2026-08-09-14-55-27\ai-douyin-mixcut`
- Root-level handoff documents: `zcode-root`
- ZCode session plans: `session-plans`
- ZCode commands: `commands`
- Asset-recognition snapshots: `asset-archives`
- Supervisor scripts and draft records: `monitor` (raw state/activity logs stay local)

The original C-drive project remains unchanged as a rollback copy. The D-drive
project is the active development location for this task.

## Snapshot

- Snapshot date: 2026-08-26
- Current application version: `2.2.150`
- Local development history: 65 commits through `fd860676944ab7d54f56a4777f4bcf0bdf86d548`
- GitHub share snapshot: `ac197e608c3deed4ee3f483ea203ccdb2f7cd502`
- Running D-drive instance: `http://127.0.0.1:8762/`
- The full commit index is maintained in `GIT_HISTORY.md`; the raw machine runtime remains on D and is intentionally excluded.

## Handling rules

- Keep project work, build output, media, and caches on D when practical.
- Preserve uncommitted files unless their ownership and purpose are clear.
- Do not commit `.env`, backups, passwords, API keys, or machine credentials.
- Do not modify the independent `FixedOrderPresets` module during OUT-2C work.
- Review the roadmap and run focused tests before starting a new module.
