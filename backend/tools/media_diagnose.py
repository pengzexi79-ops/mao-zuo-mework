import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# ---------------------------------------------------------------------------
# Mework 本地媒体诊断脚本
#   1) --image            RapidOCR 画面文字识别（多帧）
#   2) --audio            ASR 转写，返回带时间戳的字幕 cues
#                         --asr-engine auto|whispercpp|faster-whisper
#                           auto = whisper.cpp（本地快速）优先，失败回退 faster-whisper
#   3) --video-quality    OpenCV 画面质量分析（模糊帧 / 暗帧 / 过曝帧比例）
# ---------------------------------------------------------------------------

parser = argparse.ArgumentParser()
parser.add_argument("--image", action="append")
parser.add_argument("--audio")
parser.add_argument("--asr-engine", default="auto",
                    choices=["auto", "whispercpp", "faster-whisper"])
parser.add_argument("--video-quality")
args = parser.parse_args()


def find_whisper_cli():
    """whisper.cpp 可执行文件解析：环境变量 WHISPER_CLI > PATH > 脚本同级/上级 portable 目录。"""
    env = os.environ.get("WHISPER_CLI", "").strip()
    if env and Path(env).is_file():
        return env
    on_path = shutil.which("whisper-cli")
    if on_path:
        return on_path
    here = Path(__file__).resolve().parent
    # 开发/安装布局：backend/tools -> 项目根/portable/whisper/Release/whisper-cli.exe
    for root in (here.parent.parent, here.parent.parent.parent):
        candidate = root / "portable" / "whisper" / "Release" / "whisper-cli.exe"
        if candidate.is_file():
            return str(candidate)
    return None


def find_whisper_model():
    """ggml 模型解析：WHISPER_MODEL > 常见预置目录（优先 small，其次 base，再任意 ggml-*.bin）。"""
    env = os.environ.get("WHISPER_MODEL", "").strip()
    if env and Path(env).is_file():
        return env
    here = Path(__file__).resolve().parent
    candidates = []
    for root in (here.parent.parent, here.parent.parent.parent):
        candidates.append(root / "portable" / "whisper-models")
        candidates.append(root / "portable" / "whisper" / "models")
    for prefer in ("ggml-small.bin", "ggml-base.bin", "ggml-tiny.bin"):
        for dir_ in candidates:
            p = dir_ / prefer
            if p.is_file():
                return str(p)
    for dir_ in candidates:
        if dir_.is_dir():
            for p in sorted(dir_.glob("ggml-*.bin")):
                return str(p)
    return None


def run_whispercpp(audio_path):
    """调用 whisper.cpp 生成 JSON 输出，解析为与 faster-whisper 一致的 segments。"""
    cli = find_whisper_cli()
    model = find_whisper_model()
    if not cli or not model:
        raise RuntimeError("whisper.cpp 二进制或 ggml 模型缺失")
    with tempfile.TemporaryDirectory() as tmp:
        prefix = str(Path(tmp) / "whisper-out")
        cmd = [cli, "-m", model, "-f", audio_path, "-oj", "-of", prefix,
               "--output-json", "-np"]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
        if proc.returncode != 0:
            raise RuntimeError("whisper.cpp failed: " + proc.stderr[-500:])
        out_file = Path(prefix + ".json")
        if not out_file.is_file():
            raise RuntimeError("whisper.cpp 未生成 JSON 输出")
        data = json.loads(out_file.read_text(encoding="utf-8"))
    segments = []
    for item in data.get("transcription", []):
        text = (item.get("text") or "").strip()
        if not text:
            continue
        offsets = item.get("offsets", {})
        segments.append({
            "start": round(float(offsets.get("from", 0)), 3),
            "end": round(float(offsets.get("to", 0)), 3),
            "text": text,
        })
    if not segments:
        raise RuntimeError("whisper.cpp 无有效转写结果")
    return {"engine": "whisper.cpp", "language": "zh", "segments": segments}


def run_faster_whisper(audio_path):
    from faster_whisper import WhisperModel
    model = WhisperModel("small", device="cpu", compute_type="int8")
    segments, info = model.transcribe(
        audio_path,
        language=None,
        vad_filter=True,
        beam_size=5,
        word_timestamps=True,
        initial_prompt="以下是普通话的句子。",
    )
    cues = []
    for segment in segments:
        text = (segment.text or "").strip()
        if not text:
            continue
        words = getattr(segment, "words", None) or []
        start = segment.start
        end = segment.end
        if words:
            starts = [w.start for w in words if getattr(w, "start", None) is not None]
            ends = [w.end for w in words if getattr(w, "end", None) is not None]
            if starts:
                start = min(starts)
            if ends:
                end = max(ends)
        cues.append({"start": round(start, 3), "end": round(end, 3), "text": text})
    return {"engine": "faster-whisper", "language": info.language, "segments": cues}


def analyze_video_quality(path):
    """OpenCV 逐帧采样：统计模糊帧、暗帧、过曝帧占比，补充 FFmpeg 基础闸门。"""
    import cv2
    import numpy as np
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        return {"readable": False}
    total = 0
    blurry = 0
    dark = 0
    bright = 0
    step = 10
    frame_idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if frame_idx % step != 0:
            frame_idx += 1
            continue
        frame_idx += 1
        total += 1
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        mean = float(gray.mean())
        # 拉普拉斯方差衡量清晰度：低于阈值视为模糊帧
        lap = cv2.Laplacian(gray, cv2.CV_64F).var()
        if lap < 40.0:
            blurry += 1
        if mean < 18.0:
            dark += 1
        if mean > 235.0:
            bright += 1
    cap.release()
    if total == 0:
        return {"readable": False}
    return {
        "readable": True,
        "frames": total,
        "blurryRatio": round(blurry / total, 4),
        "darkRatio": round(dark / total, 4),
        "brightRatio": round(bright / total, 4),
    }


def main():
    if args.video_quality:
        result = analyze_video_quality(args.video_quality)
        print(json.dumps(result, ensure_ascii=False))
        return
    if args.image:
        from rapidocr_onnxruntime import RapidOCR
        engine = RapidOCR()
        texts = []
        for image in args.image:
            result, _ = engine(image)
            if not result:
                continue
            for item in result:
                text = str(item[1]).strip()
                if text and len(text) >= 1 and text not in texts:
                    texts.append(text)
        print(json.dumps({"ocrTexts": texts[:60]}, ensure_ascii=False))
        return
    if args.audio:
        if args.asr_engine == "auto":
            errors = []
            try:
                print(json.dumps(run_whispercpp(args.audio), ensure_ascii=False))
                return
            except Exception as exc:
                errors.append("whisper.cpp: " + str(exc))
            try:
                print(json.dumps(run_faster_whisper(args.audio), ensure_ascii=False))
                return
            except Exception as exc:
                errors.append("faster-whisper: " + str(exc))
            raise SystemExit("ASR 全部引擎失败：" + " | ".join(errors))
        if args.asr_engine == "whispercpp":
            print(json.dumps(run_whispercpp(args.audio), ensure_ascii=False))
            return
        print(json.dumps(run_faster_whisper(args.audio), ensure_ascii=False))
        return
    raise SystemExit("provide --image, --audio or --video-quality")


if __name__ == "__main__":
    main()
