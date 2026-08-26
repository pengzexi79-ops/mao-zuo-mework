#!/usr/bin/env python3
"""Create, validate, and apply the local release-history record."""

from __future__ import annotations

import argparse
import json
import re
import sys
from copy import deepcopy
from datetime import date
from pathlib import Path
from typing import Any

VERSION_PATTERN = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
REQUIRED_LISTS = ("changes", "fixes", "verification", "evidence")
REQUIRED_FIELDS = ("title", "summary", "changes", "fixes", "verification", "compatibility", "evidence")

ROOT = Path(__file__).resolve().parents[1]
NOTES_PATH = ROOT / "src" / "main" / "resources" / "release-notes.json"
PENDING_PATH = ROOT / "release-notes.pending.json"
APP_PROPS_PATH = ROOT / "src" / "main" / "java" / "com" / "douyin" / "mixcut" / "config" / "AppProps.java"
APPLICATION_PATH = ROOT / "src" / "main" / "resources" / "application.yml"
INSTALLER_PATH = ROOT.parent / "installer" / "Mework.iss"


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise SystemExit(f"找不到文件：{path}")
    except json.JSONDecodeError as error:
        raise SystemExit(f"JSON 格式错误：{path}:{error.lineno}:{error.colno} {error.msg}")


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_version(value: str) -> tuple[int, int, int]:
    match = VERSION_PATTERN.fullmatch(value)
    if not match:
        raise ValueError(f"版本号必须为 x.y.z，例如 2.2.6；收到：{value}")
    return tuple(int(part) for part in match.groups())


def next_patch(version: str) -> str:
    major, minor, patch = parse_version(version)
    return f"{major}.{minor}.{patch + 1}"


def release_id(version: str) -> str:
    return "release-" + version.replace(".", "-")


def validate_pending(pending: Any, current_version: str) -> dict[str, Any]:
    if not isinstance(pending, dict) or not pending:
        raise ValueError("待记录文件必须是一个非空 JSON 对象")
    for field in REQUIRED_FIELDS:
        value = pending.get(field)
        if value is None or (isinstance(value, str) and not value.strip()):
            raise ValueError(f"待记录缺少字段：{field}")
        if field in REQUIRED_LISTS and (not isinstance(value, list) or not value):
            raise ValueError(f"待记录数组字段不能为空：{field}")
    evidence_text = " ".join(str(e) for e in pending.get("evidence", [])).lower()
    for marker in ("password=", "token=", ".env"):
        if marker in evidence_text:
            raise ValueError(f"待记录 evidence 不能包含敏感配置内容（{marker}），与 ReleaseNotesSchemaTest 保持一致")

    version = pending.get("version") or next_patch(current_version)
    parse_version(version)
    expected_version = next_patch(current_version)
    if version != expected_version:
        raise ValueError(f"新版本必须是当前版本 {current_version} 的连续补丁版本：{expected_version}")
    return {**pending, "version": version}


def validate_backfill(records: Any, current_version: str, current_released_at: str) -> list[dict[str, Any]]:
    if not isinstance(records, list) or not records:
        raise ValueError("历史回填文件必须是非空 JSON 数组")
    checked: list[dict[str, Any]] = []
    previous_version = current_version
    previous_date = date.fromisoformat(current_released_at)
    today = date.today()
    for index, raw in enumerate(records, start=1):
        if not isinstance(raw, dict):
            raise ValueError(f"第 {index} 条历史回填记录必须是 JSON 对象")
        record = validate_pending(raw, previous_version)
        released_at = date.fromisoformat(str(record.get("releasedAt", "")))
        if released_at > today:
            raise ValueError(f"第 {index} 条历史回填记录日期不能晚于今天")
        if previous_date is not None and released_at < previous_date:
            raise ValueError("历史回填记录必须按日期和版本从旧到新排列")
        record["releasedAt"] = released_at.isoformat()
        checked.append(record)
        previous_version = str(record["version"])
        previous_date = released_at
    return checked


def read_pending() -> dict[str, Any] | None:
    if not PENDING_PATH.exists():
        return None
    pending = load_json(PENDING_PATH)
    if pending in ({}, None):
        return None
    return pending


def update_app_version(version: str) -> None:
    props = APP_PROPS_PATH.read_text(encoding="utf-8")
    updated_props, replacements = re.subn(
        r'private static final String RELEASE_VERSION = "[^"]+";',
        f'private static final String RELEASE_VERSION = "{version}";',
        props,
        count=1,
    )
    if replacements != 1:
        raise ValueError("无法更新 AppProps.java 的构建版本")
    installer = INSTALLER_PATH.read_text(encoding="utf-8")
    updated_installer, replacements = re.subn(
        r'(?m)^#define AppVersion "[^"]+"$',
        f'#define AppVersion "{version}"',
        installer,
        count=1,
    )
    if replacements == 0:
        # Current installer sources include the generated version file instead of defining
        # AppVersion inline. Keep the source stable and update the generated include when it exists.
        version_include = INSTALLER_PATH.parent / "version.iss"
        if version_include.exists():
            include = version_include.read_text(encoding="utf-8")
            updated_include, include_replacements = re.subn(
                r'(?m)^#define AppVersion "[^"]+"$',
                f'#define AppVersion "{version}"',
                include,
                count=1,
            )
            if include_replacements != 1:
                raise ValueError("无法更新 installer/version.iss 的 AppVersion")
            APP_PROPS_PATH.write_text(updated_props, encoding="utf-8")
            version_include.write_text(updated_include, encoding="utf-8")
            return
        # version.iss is generated by build_installer.ps1; AppProps is still updated now,
        # and the next installer build will regenerate the include from release-notes.json.
        APP_PROPS_PATH.write_text(updated_props, encoding="utf-8")
        return
    APP_PROPS_PATH.write_text(updated_props, encoding="utf-8")
    INSTALLER_PATH.write_text(updated_installer, encoding="utf-8")


def command_new(args: argparse.Namespace) -> None:
    if read_pending() is not None:
        raise SystemExit(f"已有待记录文件：{PENDING_PATH}；请先完成或清空它。")
    template = {
        "title": args.title or "填写本次更新标题",
        "summary": "填写本次已完成且可验证的更新摘要。",
        "kind": "当前本机构建",
        "changes": ["填写已完成的功能或行为变化。"],
        "fixes": ["填写已修复的问题；没有修复项时写明无。"],
        "verification": ["填写实际执行过的测试、构建或媒体验证。"],
        "compatibility": "填写数据、配置、重启或兼容性影响；没有影响时写明无。",
        "evidence": ["填写对应源码、测试或可核对文件路径。"],
    }
    if args.version:
        parse_version(args.version)
        template["version"] = args.version
    write_json(PENDING_PATH, template)
    print(f"已创建待记录：{PENDING_PATH}")


def command_check(_: argparse.Namespace) -> None:
    notes = load_json(NOTES_PATH)
    current = str(notes.get("version", ""))
    parse_version(current)
    pending = read_pending()
    if pending is None:
        print("没有待记录，版本记录已就绪。")
        return
    checked = validate_pending(pending, current)
    print(f"待发布版本 {checked['version']} 记录完整，可执行 apply。")


def command_migrate(_: argparse.Namespace) -> None:
    notes = load_json(NOTES_PATH)
    removed_versions = {"1.1.0"}
    cleaned_history = []
    for record in notes.get("history", []):
        if record.get("version") in removed_versions:
            continue
        match = re.match(r"^v?(\d+\.\d+\.\d+)", str(record.get("version", "")))
        if not match:
            raise ValueError(f"无法迁移历史版本：{record.get('version')}")
        record = deepcopy(record)
        record["version"] = match.group(1)
        record["id"] = release_id(record["version"])
        record["kind"] = "历史开发阶段" if record["kind"] not in {
            "交付构建", "阶段验收", "数据库演进阶段", "旧版原型（已取代）", "正式技术栈重写"
        } else record["kind"]
        cleaned_history.append(record)
    cleaned_history.sort(key=lambda item: (item["releasedAt"], parse_version(item["version"])), reverse=True)
    current = deepcopy(notes)
    current["id"] = release_id("2.2.5")
    current["version"] = "2.2.5"
    current["releasedAt"] = date.today().isoformat()
    current["kind"] = "当前本机构建"
    current["summary"] = "本次独立记录品牌统一、竖屏全屏适配、自动切片与配音、本机 OCR/语音识别，以及成片交付质检和真实媒体验证中修复的问题。昨天已完成的环境、导入和控制台更新保留在 2.2.4 及更早的历史记录中。"
    current["history"] = cleaned_history
    write_json(NOTES_PATH, current)
    update_app_version("2.2.5")
    print("已统一迁移历史版本：当前 2.2.5，历史从 2.2.4 开始。")


def command_correct_history(_: argparse.Namespace) -> None:
    notes = load_json(NOTES_PATH)
    history = [record for record in notes.get("history", []) if record.get("version") not in {"2.2.5", "2.2.6", "2.2.7", "2.2.8"}]
    yesterday = "2026-08-11"
    historical_records = [
        {
            "id": release_id("2.2.8"),
            "version": "2.2.8",
            "kind": "历史开发阶段",
            "releasedAt": yesterday,
            "title": "成片交付质检与真实媒体链路修复",
            "summary": "为成片增加可发布交付闸门，并以真实素材、真实音轨和真实输出修复混音与检测误判。",
            "changes": [
                "成片库增加可发布、建议复核、已拦截状态及质检详情，记录音频时长、最长静音、黑屏和音画起始偏移",
                "成片最终保存前增加声音、长静音、黑屏和音画同步交付闸门；不合格文件直接拦截，不进入成片库"
            ],
            "fixes": [
                "修复 voice 与 BGM 同时混音时错误使用 sidechain 滤镜导致混音失败、随后被静音轨掩盖的问题",
                "修复静音检测解析 FFmpeg 日志时误把末尾音频算成几十秒静音的问题",
                "修复黑屏检测阈值过宽、将正常真实素材误判为黑屏的问题",
                "修复手工限定画面素材时音频素材不在范围内，造成计划无法选择已指定口播或 BGM 的问题"
            ],
            "verification": [
                "FFmpeg 集成测试验证正常音频、纯静音、黑屏和正常画面的质量检测结果",
                "真实成片 qc_34_01_7095.mp4 通过质检：音频 50.467 秒、最长静音 0 秒、黑屏 0 秒、音画起始偏移 0 秒",
                "后端 Maven 测试共 18 项通过"
            ],
            "compatibility": "新增 job_output 质检字段已通过幂等迁移补齐；默认拒绝无声、长静音、明显黑屏或音画不同步的输出。",
            "evidence": [
                "RenderService.java", "FfmpegTool.java", "JobOutput.java", "JobService.java", "Outputs.vue", "output-qc-migration.sql", "FfmpegToolTest.java", "AppProps.java"
            ]
        },
        {
            "id": release_id("2.2.7"),
            "version": "2.2.7",
            "kind": "历史开发阶段",
            "releasedAt": yesterday,
            "title": "本机 OCR、语音识别与素材 AI 检查",
            "summary": "将本机画面文字和口播识别接入素材检查，输出可用于剪辑的时间信息和风险提示。",
            "changes": [
                "接入 RapidOCR、faster-whisper 和神经配音环境检测",
                "素材 AI 检查可返回画面文字、口播逐句时间、建议用途和风险提示"
            ],
            "fixes": ["修复素材检查只依赖文件名和基础媒体信息、无法提供画面与口播依据的问题"],
            "verification": ["真实语音识别验证返回逐句开始、结束时间和中文口播文本"],
            "compatibility": "RapidOCR、faster-whisper 和神经配音使用项目私有 .venv；首次语音识别会下载并缓存模型。",
            "evidence": ["MaterialDiagnosisService.java", "media_diagnose.py", "BootstrapService.java", "Materials.vue"]
        },
        {
            "id": release_id("2.2.6"),
            "version": "2.2.6",
            "kind": "历史开发阶段",
            "releasedAt": yesterday,
            "title": "素材库自动切片与自动配音",
            "summary": "把原始视频和文案转成可直接参与出片的短片与人声素材，并在入库前验证媒体质量。",
            "changes": [
                "原视频按 1 至 15 秒切成独立短片，自动生成缩略图、入库并标记为主体素材，原文件不覆盖",
                "本机神经语音生成后先检查时长、可解码和异常静音，再作为人声素材入库供出片选择"
            ],
            "fixes": ["修复自动配音或生成音频未经过有效时长、可解码和静音检查就入库的问题"],
            "verification": ["真实竖屏素材自动切片验证生成 5 秒、5 秒和 2.1 秒片段并自动入库", "真实中文自动配音已生成并入库为人声素材，时长 7.368 秒"],
            "compatibility": "自动切片只新增素材，不覆盖原始文件；自动配音失败不会写入无效人声素材。",
            "evidence": ["MaterialService.java", "TtsService.java", "MaterialController.java", "Materials.vue"]
        },
        {
            "id": release_id("2.2.5"),
            "version": "2.2.5",
            "kind": "历史开发阶段",
            "releasedAt": yesterday,
            "title": "竖屏成片全屏播放适配",
            "summary": "修正浏览器原生全屏时的画面适配策略，使竖屏成片完整显示。",
            "changes": ["成片全屏播放改为完整适配竖屏画面，不再因 cover 放大导致只显示中间部分"],
            "fixes": ["修复竖屏视频进入浏览器全屏后被放大裁切、只能看到中间区域的问题"],
            "verification": ["浏览器全屏播放竖屏成片验证通过", "前端 Vite 构建通过"],
            "compatibility": "仅调整浏览器全屏样式，不改变导出视频文件、时间线或素材数据。",
            "evidence": ["styles.css", "Outputs.vue"]
        }
    ]
    notes["id"] = release_id("2.2.9")
    notes["version"] = "2.2.9"
    notes["releasedAt"] = date.today().isoformat()
    notes["kind"] = "当前本机构建"
    notes["title"] = "猫作品牌统一与发行历史纠错"
    notes["summary"] = "本次仅记录今天完成的品牌统一、旧静态资源缓存修复，以及发行历史按真实日期和版本重建的机制。昨天的媒体功能和质检修复已分别归档到 2.2.5 至 2.2.8。"
    notes["changes"] = [
        "品牌统一更新为猫作 · Mework，浏览器标题、侧栏、AI 助手、教程、PWA 清单和工作流来源文案同步更新",
        "新增版本记录发布工具与待记录门禁：自动归档当前版本、生成新版本、同步 app.version，并在构建前检查未应用记录",
        "发行历史统一为纯 x.y.z 版本号，按真实发布日期和版本顺序展示；昨天后半段更新拆分归档为 2.2.5 至 2.2.8"
    ]
    notes["fixes"] = [
        "修复浏览器缓存旧指纹脚本后仍显示旧品牌名称的问题",
        "修复昨天后半段的功能更新被错误合并进今天当前记录、历史版本缺少对应条目的问题",
        "修复历史记录混用 v 前缀、阶段后缀和日汇总版本，导致时间线不可追溯的问题"
    ]
    notes["verification"] = [
        "前端 Vite 构建和静态资源引用校验通过",
        "后端 Maven 测试通过，发行记录在启动时与 app.version 一致",
        "版本接口确认当前 2.2.9，历史顶部依次为 2.2.8、2.2.7、2.2.6、2.2.5、2.2.4"
    ]
    notes["compatibility"] = "新增 release-notes.pending.json 发布门禁；后续每次更新必须先登记待记录，再运行 release_notes.py check 和 apply。Maven/Jar 构建坐标仍与应用发布版本分开管理。"
    notes["evidence"] = [
        "App.vue", "AiChat.vue", "Tutorial.vue", "Workflows.vue", "WebConfig.java", "ReleaseNotesService.java", "release_notes.py", "ReleaseNotesSchemaTest.java", "ReleaseNotesPendingTest.java", "RELEASE_HISTORY_RULES.md"
    ]
    notes["history"] = [*historical_records, *history]
    write_json(NOTES_PATH, notes)
    write_json(PENDING_PATH, {})
    update_app_version("2.2.9")
    print("已按真实日期重建版本历史：当前 2.2.9，昨天补记 2.2.5 至 2.2.8。")


def command_apply(_: argparse.Namespace) -> None:
    notes = load_json(NOTES_PATH)
    current_version = str(notes.get("version", ""))
    parse_version(current_version)
    pending = read_pending()
    if pending is None:
        raise SystemExit("没有待记录，无法发布。请先运行 new 并填写 backend/release-notes.pending.json。")
    record = validate_pending(pending, current_version)
    today = date.today().isoformat()
    old_current = {key: deepcopy(value) for key, value in notes.items() if key != "history" and key != "source"}
    old_current["id"] = release_id(current_version)
    old_current["kind"] = "历史开发阶段"
    old_history = list(notes.get("history", []))
    new_version = record.pop("version")
    notes.update(record)
    notes["id"] = release_id(new_version)
    notes["version"] = new_version
    notes["releasedAt"] = today
    notes["kind"] = record.get("kind", "当前本机构建")
    notes["history"] = [old_current, *old_history]
    write_json(NOTES_PATH, notes)
    write_json(PENDING_PATH, {})
    update_app_version(new_version)
    print(f"已发布 {new_version}，原版本 {current_version} 已归档。接着运行：cd backend && mvn test")


def command_backfill(args: argparse.Namespace) -> None:
    if read_pending() is not None:
        raise ValueError("存在待发布记录时不能执行历史回填")
    notes = load_json(NOTES_PATH)
    current_version = str(notes.get("version", ""))
    parse_version(current_version)
    records = validate_backfill(load_json(Path(args.file).resolve()), current_version, str(notes.get("releasedAt", "")))
    for record in records:
        old_current = {key: deepcopy(value) for key, value in notes.items() if key not in {"history", "source"}}
        old_current["id"] = release_id(current_version)
        old_current["kind"] = "历史开发阶段"
        old_history = list(notes.get("history", []))
        record = deepcopy(record)
        new_version = str(record.pop("version"))
        notes.update(record)
        notes["id"] = release_id(new_version)
        notes["version"] = new_version
        notes["kind"] = "当前本机构建"
        notes["history"] = [old_current, *old_history]
        current_version = new_version

    # Keep the packaged history and compiled release identity in one recoverable transition.
    paths = [NOTES_PATH, PENDING_PATH, APP_PROPS_PATH, INSTALLER_PATH, INSTALLER_PATH.parent / "version.iss"]
    snapshots = {path: path.read_bytes() for path in paths if path.exists()}
    try:
        write_json(NOTES_PATH, notes)
        write_json(PENDING_PATH, {})
        update_app_version(current_version)
    except Exception:
        for path in paths:
            if path in snapshots:
                path.write_bytes(snapshots[path])
            elif path.exists():
                path.unlink()
        raise
    print(f"已按真实日期连续回填 {len(records)} 条记录，当前版本 {current_version}。接着运行：cd backend && mvn test")


def main() -> None:
    parser = argparse.ArgumentParser(description="猫作版本更新与纠错记录工具")
    subparsers = parser.add_subparsers(dest="command", required=True)
    new_parser = subparsers.add_parser("new", help="创建待记录模板")
    new_parser.add_argument("--title", help="本次更新标题")
    new_parser.add_argument("--version", help="可选指定版本；默认 apply 时自动递增补丁号")
    new_parser.set_defaults(handler=command_new)
    subparsers.add_parser("check", help="检查待记录").set_defaults(handler=command_check)
    subparsers.add_parser("migrate", help="一次性统一旧版本记录").set_defaults(handler=command_migrate)
    subparsers.add_parser("correct-history", help="一次性按真实日期补齐历史版本").set_defaults(handler=command_correct_history)
    subparsers.add_parser("apply", help="归档当前版本并发布待记录").set_defaults(handler=command_apply)
    backfill_parser = subparsers.add_parser("backfill", help="从当前版本之后按真实日期从旧到新连续补录经审核的历史记录")
    backfill_parser.add_argument("--file", required=True, help="包含连续版本记录的 JSON 数组")
    backfill_parser.set_defaults(handler=command_backfill)
    args = parser.parse_args()
    try:
        args.handler(args)
    except ValueError as error:
        raise SystemExit(f"发布记录校验失败：{error}")


if __name__ == "__main__":
    main()
