package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.NarrationCaption;
import com.douyin.mixcut.repository.NarrationCaptionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NarrationServiceTest {

    @Mock private TtsService ttsService;
    @Mock private MaterialDiagnosisService diagnosisService;
    @Mock private NarrationCaptionStore captionStore;

    @Test
    void generateSynthesizesThenTranscribesAndPersistsCompletedCaption() {
        Material voice = voice();
        when(ttsService.synthesize(anyString(), anyString(), nullable(Integer.class))).thenReturn(voice);
        when(diagnosisService.transcribeAudioFile(any(Path.class))).thenReturn(List.of(cue(0.0, 2.5, "你好")));
        when(captionStore.save(any(NarrationCaption.class))).thenAnswer(inv -> inv.getArgument(0));

        NarrationService service = new NarrationService(ttsService, diagnosisService, captionStore);
        NarrationService.NarrationResult result = service.generate("你好世界", "zh-CN-XiaoxiaoNeural", 10L, 1);

        assertEquals(voice, result.voice());
        assertEquals(1, result.cues().size());

        ArgumentCaptor<NarrationCaption> captor = ArgumentCaptor.forClass(NarrationCaption.class);
        verify(captionStore).save(captor.capture());
        assertEquals("completed", captor.getValue().getStatus());
        assertEquals(10L, captor.getValue().getJobId());
        assertEquals(1, captor.getValue().getIdx());
        assertEquals(voice.getId(), captor.getValue().getVoiceMaterialId());
    }

    @Test
    void generateStillReturnsVoiceWhenAsrReturnsNoCues() {
        Material voice = voice();
        when(ttsService.synthesize(anyString(), anyString(), nullable(Integer.class))).thenReturn(voice);
        when(diagnosisService.transcribeAudioFile(any(Path.class))).thenReturn(List.of());
        when(captionStore.save(any(NarrationCaption.class))).thenAnswer(inv -> inv.getArgument(0));

        NarrationService service = new NarrationService(ttsService, diagnosisService, captionStore);
        NarrationService.NarrationResult result = service.generate("你好世界", "zh-CN-XiaoxiaoNeural", 10L, 1);

        assertNotNull(result.voice());
        assertTrue(result.cues().isEmpty());

        ArgumentCaptor<NarrationCaption> captor = ArgumentCaptor.forClass(NarrationCaption.class);
        verify(captionStore).save(captor.capture());
        assertEquals("no_cues", captor.getValue().getStatus());
    }

    private Material voice() {
        Material material = new Material();
        material.setId(77L);
        material.setName("voice.mp3");
        material.setFilePath("C:/data/materials/generated-voice/voice.mp3");
        material.setFileType(Material.FileType.audio);
        return material;
    }

    private MaterialDiagnosisService.TranscriptCue cue(double start, double end, String text) {
        MaterialDiagnosisService.TranscriptCue cue = new MaterialDiagnosisService.TranscriptCue();
        cue.setStart(start);
        cue.setEnd(end);
        cue.setText(text);
        return cue;
    }
}
