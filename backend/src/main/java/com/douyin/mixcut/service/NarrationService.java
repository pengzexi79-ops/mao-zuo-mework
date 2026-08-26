package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.NarrationCaption;
import com.douyin.mixcut.repository.NarrationCaptionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * Real AI narration pipeline: synthesize the voice first, then run ASR over the generated audio
 * to obtain the actual caption timeline. The ASR cues are the single authority for AI voice
 * subtitles — script text is never used to guess timing.
 *
 * <p>The generated voice is always usable on its own; ASR failure only means "no AI voice
 * subtitles", never a blocked render. Both outcomes are persisted as a task-level
 * {@link NarrationCaption} for audit and restart reuse.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NarrationService {

    private final TtsService ttsService;
    private final MaterialDiagnosisService diagnosisService;
    private final NarrationCaptionStore captionStore;
    private final ObjectMapper om = new ObjectMapper();

    /** Voice + script + real ASR cues (empty when ASR returned no usable timeline). */
    public record NarrationResult(Material voice, String script, List<MaterialDiagnosisService.TranscriptCue> cues) {
    }

    public NarrationResult generate(String script, String voice, Long jobId, int idx) {
        return generate(script, voice, jobId, idx, null);
    }

    public NarrationResult generate(String script, String voice, Long jobId, int idx, Integer targetSec) {
        Material voiceMaterial = ttsService.synthesize(script, voice, targetSec);
        List<MaterialDiagnosisService.TranscriptCue> cues =
                diagnosisService.transcribeAudioFile(Path.of(voiceMaterial.getFilePath()));

        NarrationCaption caption = new NarrationCaption();
        caption.setJobId(jobId);
        caption.setIdx(idx);
        caption.setVoiceMaterialId(voiceMaterial.getId());
        caption.setScriptText(script);
        caption.setCues(writeCues(cues));
        if (cues.isEmpty()) {
            caption.setStatus("no_cues");
            caption.setError("ASR 未返回有效时间轴，已跳过 AI 配音字幕");
        } else {
            caption.setStatus("completed");
        }
        captionStore.save(caption);
        return new NarrationResult(voiceMaterial, script, cues);
    }

    public java.util.List<String> scriptsByJobId(Long jobId) {
        return captionStore.scriptsByJobId(jobId);
    }

    public void deleteByJobId(Long jobId) {
        captionStore.deleteByJobId(jobId);
    }

    private String writeCues(List<MaterialDiagnosisService.TranscriptCue> cues) {
        try {
            return om.writeValueAsString(cues);
        } catch (Exception e) {
            return "[]";
        }
    }
}
