---
description: Run the Mework AI material director workflow with safe environment audit, AI material autofill, subtitle cleanup, and video content survey.
argument-hint: [optional project/product brief]
---

Act as the Mework AI material director for this workspace. Continue until the app is verified or a hard blocker is proven.

Project path: `C:\Users\Windows\WorkBuddy\2026-08-09-14-55-27\ai-douyin-mixcut`.

User brief: `$ARGUMENTS`.

Hard rules:
- First audit the real environment and current code. Do not install IndexTTS2, PyTorch, CUDA, Python, FFmpeg, Node, Java, or model dependencies before the audit.
- Reuse existing Edge-TTS, ChatTTS, ASR, Demucs, FFmpeg, ProcRunner, capability/status, task/log/config, material, workflow, and render systems wherever they already exist.
- Only install missing components when the app's own environment checks or build/test output proves they are missing. Use the project's existing private runtime paths and bootstrap scripts. Do not overwrite or upgrade core dependencies unless a verified bug requires it.
- Do not expose arbitrary local paths or commands to the browser. Do not execute model commands from frontend state. Browser choices may only map to bounded backend parameters.
- Keep all generated media in managed material/cache/output directories, and clean temporary files.

Required workflow:
1. Inspect backend/frontend code and the current runtime status for FFmpeg, FFprobe, local Python, ASR/OCR, Demucs, TTS, Node, Maven, and Java.
2. Verify the workflow pack at `docs/workflows/mework-ai-material-director.mixcut-workflow.json` imports successfully or update it to the current validated workflow DSL.
3. In the Studio page, verify users can choose whether the app AI may introduce missing public materials before render. The render payload must carry `autoUseCrawledMaterials` and preparation must use only fixed public sources controlled by the backend.
4. Verify users can choose source subtitle cleanup. The browser may only choose `off` or `subtitle-safe-band`; the backend must perform controlled FFmpeg filters, not accept custom filters.
5. Run a video content survey from a top editor / slice editor / ecommerce perspective:
   - Identify product, category, visible scenes, people, product closeups, usage moments, proof shots, old subtitles, watermarks, black frames, empty lead-in/outro, duplicate shots, audio roles, and selling-point coverage.
   - Judge whether the first 3 seconds can retain attention and whether product proof appears early enough.
   - Prefer short varied slices, strict non-overlap, product insertions, voice-first mix, low BGM, and no stale captions.
6. Build and test. If a required plugin/environment is missing, fix it conservatively through the project's existing scripts or package managers, then rebuild.
7. If code changes are made, record a release note according to `docs/RELEASE_HISTORY_RULES.md`.
8. Final report must include changed files, environment status, what was installed or intentionally not installed, verification commands, and remaining risks.
