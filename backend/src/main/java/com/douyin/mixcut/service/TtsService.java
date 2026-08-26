package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Controlled local neural TTS entry point. Only text and a known voice ID reach edge-tts. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {
    private static final int MAX_TEXT_LENGTH = 1200;
    private static final List<String> ALLOWED_VOICES = List.of(
            "zh-CN-XiaoxiaoNeural", "zh-CN-XiaoyiNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural");

    private final AppProps props;
    private final ProcRunner runner;
    private final FfmpegTool ffmpeg;
    private final MaterialService materials;
    private final AudioContractService audioContractService;

    public boolean available() {
        return runner.run(List.of(props.localPythonPath(), "-c", "import edge_tts"), 12).ok();
    }

    /** Optional natural narration engine (ChatTTS). Auto-enabled when installed in the bundled venv. */
    public boolean naturalAvailable() {
        return runner.run(List.of(props.localPythonPath(), "-c", "import ChatTTS"), 12).ok();
    }

    public Material synthesize(String text, String voice) {
        return synthesize(text, voice, null);
    }

    public Material synthesize(String text, String voice, Integer targetSec) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) throw new IllegalArgumentException("请先填写需要配音的文案");
        if (clean.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("单次配音文案不能超过 " + MAX_TEXT_LENGTH + " 个字符");
        if (!available()) throw new IllegalStateException("本机神经配音工具不可用，请检查项目 .venv 环境");
        String selectedVoice = ALLOWED_VOICES.contains(voice) ? voice : ALLOWED_VOICES.get(0);
        try {
            Path dir = props.materials().resolve("generated-voice");
            Files.createDirectories(dir);
            Path output = dir.resolve("voice_" + System.currentTimeMillis() + ".mp3");
            Path naturalScript = props.mediaDiagnoseScriptPath().getParent().resolve("natural_tts.py");
            boolean useNatural = Files.isRegularFile(naturalScript) && naturalAvailable();
            if (useNatural) {
                ProcRunner.Result natural = runner.run(List.of(props.localPythonPath(), naturalScript.toString(),
                        "--text", clean, "--output", output.toString()), 300);
                if (natural.ok() && Files.isRegularFile(output) && Files.size(output) > 1024) {
                    try {
                        validateVoiceOutput(output, "natural-tts");
                        Material material = materials.register(output.toString(), null, false, Material.Source.generated, null);
                        material.setRole(MaterialRole.voice);
                        material.setTags("自然配音," + selectedVoice);
                        return materials.save(material);
                    } catch (Exception rejected) {
                        log.warn("natural TTS audio admission rejected: {}", rejected.getMessage());
                    }
                }
                Files.deleteIfExists(output);
                log.warn("natural TTS failed, falling back to edge-tts: {}", natural.out());
            }
            List<String> cmd = new ArrayList<>(List.of(props.localPythonPath(), "-m", "edge_tts",
                    "--voice", selectedVoice));
            if (targetSec != null && targetSec > 0) {
                // 按 4.2 字/秒估算默认语速，反推语速百分比，让口播贴合计划时长，降低"超时/过短被拒"概率。
                double targetChars = targetSec * 4.2;
                int rate = (int) Math.round((clean.length() / Math.max(1, targetChars) - 1) * 100);
                rate = Math.max(-50, Math.min(50, rate));
                cmd.add("--rate=" + (rate >= 0 ? "+" : "") + rate + "%");
            }
            cmd.add("--text");
            cmd.add(clean);
            cmd.add("--write-media");
            cmd.add(output.toString());
            ProcRunner.Result run = runner.run(cmd, 180);
            if (!run.ok() || !Files.isRegularFile(output) || Files.size(output) < 1024) {
                Files.deleteIfExists(output);
                throw new IllegalStateException("配音生成失败，请检查网络或更换音色");
            }
            validateVoiceOutput(output, "edge-tts");
            Material material = materials.register(output.toString(), null, false, Material.Source.generated, null);
            material.setRole(MaterialRole.voice);
            material.setTags("自动配音," + selectedVoice);
            return materials.save(material);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("配音处理失败：" + e.getMessage(), e);
        }
    }

    private void validateVoiceOutput(Path output, String sourceType) throws Exception {
        FfmpegTool.MediaInfo info = ffmpeg.probe(output.toString());
        if (!info.isHasAudio() || info.getDuration() < 0.5) {
            Files.deleteIfExists(output);
            throw new IllegalStateException("配音文件无有效声音，已拒绝入库");
        }
        var contract = audioContractService.inspect(output.toString(), 0, sourceType,
                com.douyin.mixcut.external.ProcessRegistry.CancellationContext.none());
        var validation = audioContractService.validate(contract, 0);
        if (!validation.isEmpty()) {
            Files.deleteIfExists(output);
            throw new IllegalStateException("配音音频准入失败：" + String.join(", ", validation));
        }
    }
}
