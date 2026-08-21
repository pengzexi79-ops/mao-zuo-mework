"""Optional natural narration engine backed by ChatTTS.

Mework ships with edge-tts by default; when ChatTTS is installed in the
bundled venv, this script produces far more natural, human-sounding narration
(pauses, prosody, emotion). Install it with:

    pip install ChatTTS torch torchaudio

then verify: python tools/natural_tts.py --text "你好" --output out.wav --voice random

Exit codes: 0 = ok, 2 = engine missing, 1 = synthesis failed.
"""
import argparse
import json
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--text", required=True)
    parser.add_argument("--voice", default="random")
    parser.add_argument("--output", required=True)
    parser.add_argument("--rate", default=None)
    args = parser.parse_args()

    try:
        import ChatTTS
    except ImportError:
        print(json.dumps({"error": "ChatTTS 未安装（可选自然口播引擎）。可执行 pip install ChatTTS torch torchaudio 后重试。"}, ensure_ascii=False), file=sys.stderr)
        return 2

    try:
        chat = ChatTTS.Chat()
        chat.load(compile=False)
        params = None
        if args.voice and args.voice != "random":
            # 固定音色：用声音序号驱动（随机但本批内稳定）
            params = ChatTTS.Chat.InferCodeParams(spk_emb=chat.sample_random_speaker())
        wavs = chat.infer([args.text], use_decoder=True, params_infer_code=params)
        import torchaudio
        torchaudio.save(args.output, wavs[0][0].unsqueeze(0), sample_rate=24000)
        print(json.dumps({"engine": "ChatTTS", "ok": True}, ensure_ascii=False))
        return 0
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": "ChatTTS 合成失败: %s" % e}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())