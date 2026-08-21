package com.douyin.mixcut.service;

import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RenderServiceTest {

    @Test
    void rejectsMaterialAudioPlanWithoutAnyAudioBeforeFfmpegWork() {
        MixPlanner.Plan plan = usablePlan(50);
        plan.setRequiresExternalAudio(true);

        RenderService.RenderResult result = new RenderService(null, null, null, null)
                .render(plan, new MixParams(), "unused", step -> { });

        assertFalse(result.isOk());
        assertTrue(result.getError().contains("没有可覆盖全片的音轨"));
    }

    @Test
    void rejectsShortVoiceWithoutBgmBeforeAnySliceIsCreated() {
        MixPlanner.Plan plan = usablePlan(92);
        plan.setRequiresExternalAudio(true);
        plan.setVoicePath("C:/fixtures/short-voice.mp3");
        plan.setVoiceDurationSec(16.3);
        FfmpegTool ffmpeg = mock(FfmpegTool.class);
        when(ffmpeg.ffmpegAvailable()).thenReturn(true);

        RenderService.RenderResult result = new RenderService(null, ffmpeg, null, null)
                .render(plan, new MixParams(), "unused", step -> { });

        assertFalse(result.isOk());
        assertTrue(result.getError().contains("口播仅"));
        assertTrue(result.getError().contains("未选择 BGM"));
    }

    @Test
    void rejectsAiVoiceThatWouldBeSilentlyTruncated() {
        MixPlanner.Plan plan = usablePlan(50);
        plan.setRequiresExternalAudio(true);
        plan.setVoicePath("C:/fixtures/long-ai-voice.mp3");
        plan.setVoiceDurationSec(63.0);
        MixParams params = new MixParams();
        params.setAudioMode("ai-voice");
        FfmpegTool ffmpeg = mock(FfmpegTool.class);
        when(ffmpeg.ffmpegAvailable()).thenReturn(true);

        RenderService.RenderResult result = new RenderService(null, ffmpeg, null, null)
                .render(plan, params, "unused", step -> { });

        assertFalse(result.isOk());
        assertTrue(result.getError().contains("拒绝截断"));
    }

    private MixPlanner.Plan usablePlan(double duration) {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        plan.setMinSec(50);
        plan.setPlannedSec(duration);
        MixPlanner.Segment segment = new MixPlanner.Segment();
        segment.setDuration(duration);
        plan.getSegments().add(segment);
        return plan;
    }

    @Test
    void mapsNarrationCuesClampedToVideoDuration() {
        RenderService service = new RenderService(null, null, null, null);
        MixPlanner.Plan plan = new MixPlanner.Plan();
        MixPlanner.Plan.CaptionCue cue1 = new MixPlanner.Plan.CaptionCue();
        cue1.setStart(1.0);
        cue1.setEnd(2.5);
        cue1.setText("你好");
        MixPlanner.Plan.CaptionCue cue2 = new MixPlanner.Plan.CaptionCue();
        cue2.setStart(95.0);
        cue2.setEnd(100.0);
        cue2.setText("越界");
        plan.getNarrationCaptions().add(cue1);
        plan.getNarrationCaptions().add(cue2);

        List<FfmpegTool.Caption> captions = service.mapNarrationCaptions(plan, 90.0);

        assertEquals(1, captions.size(), "cues beyond the video duration must be dropped");
        assertEquals("你好", captions.get(0).getText());
        assertEquals(1.0, captions.get(0).getFrom(), 0.001);
        assertEquals(2.5, captions.get(0).getTo(), 0.001);
    }
}
