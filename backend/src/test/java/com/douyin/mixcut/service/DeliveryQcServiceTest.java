package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.DeliveryQc;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryQcServiceTest {

    private final AppProps props = new AppProps();
    private final DeliveryQcService service = new DeliveryQcService(props);

    private FfmpegTool.MediaInfo goodInfo() {
        FfmpegTool.MediaInfo info = new FfmpegTool.MediaInfo();
        info.setHasVideo(true);
        info.setHasAudio(true);
        info.setWidth(1080);
        info.setHeight(1920);
        info.setDuration(90);
        info.setVideoDuration(90);
        info.setAudioDuration(90);
        info.setVideoStartSec(0);
        info.setAudioStartSec(0);
        return info;
    }

    private FfmpegTool.AudioQuality goodAudio() {
        FfmpegTool.AudioQuality quality = new FfmpegTool.AudioQuality();
        quality.setReadable(true);
        quality.setMaxSilenceSec(0.2);
        return quality;
    }

    private FfmpegTool.VideoQuality goodVideo() {
        FfmpegTool.VideoQuality quality = new FfmpegTool.VideoQuality();
        quality.setReadable(true);
        quality.setBlackSec(0);
        return quality;
    }

    private MixPlanner.Plan planWithOneSegment() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        plan.setHookStrategy("CONFLICT");
        plan.setHookText("冲突开场");
        plan.setSemanticSegmentCount(3);
        plan.setGridFallbackCount(0);
        MixPlanner.Segment segment = new MixPlanner.Segment();
        segment.setMaterialId(1L);
        segment.setMaterialName("A");
        segment.setSourceStart(0);
        segment.setDuration(3);
        segment.setSourceDuration(15);
        segment.setSlot("body");
        plan.getSegments().add(segment);
        return plan;
    }

    @Test
    void passesCleanDeliveryAcrossAllNineCategories() {
        DeliveryQc report = service.assess(richPlan(), new MixParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, true, true, 5);

        assertEquals("pass", report.getStatus());
        assertEquals(9, report.getCategories().size());
        assertTrue(report.getSummary().contains("9 项质检"));
    }

    @Test
    void silentModePassesAudioGateOnlyWhenOutputHasNoAudioStream() {
        MixParams params = new MixParams();
        params.setAudioMode("silent");
        FfmpegTool.MediaInfo silentInfo = goodInfo();
        silentInfo.setHasAudio(false);
        silentInfo.setAudioDuration(0);

        DeliveryQc report = service.assess(richPlan(), params, 90,
                silentInfo, goodAudio(), goodVideo(), false, false, false, 0);

        assertEquals("pass", report.getStatus());
        assertTrue(report.getCategories().stream()
                .filter(category -> "audio".equals(category.getCategory()))
                .flatMap(category -> category.getChecks().stream())
                .anyMatch(text -> text.contains("不含音频流")));
    }

    private MixPlanner.Plan richPlan() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        plan.setHookStrategy("CONFLICT");
        plan.setHookText("冲突开场");
        plan.setSemanticSegmentCount(4);
        plan.setGridFallbackCount(0);
        String[] slots = {"hook", "body", "product", "endcard"};
        double[] durations = {2.0, 3.0, 2.0, 2.0};
        for (int i = 0; i < slots.length; i++) {
            MixPlanner.Segment segment = new MixPlanner.Segment();
            segment.setMaterialId((long) (i + 1));
            segment.setMaterialName("M" + (i + 1));
            segment.setSourceStart(0);
            segment.setDuration(durations[i]);
            segment.setSourceDuration(15);
            segment.setSlot(slots[i]);
            plan.getSegments().add(segment);
        }
        return plan;
    }

    @Test
    void blocksSilentDeliveryWhenAudioMissing() {
        FfmpegTool.MediaInfo info = goodInfo();
        info.setHasAudio(false);
        info.setAudioDuration(0);

        DeliveryQc report = service.assess(new MixPlanner.Plan(), new MixParams(), 90,
                info, goodAudio(), goodVideo(), true, false, false, 0);

        assertEquals("fail", report.getStatus());
        assertTrue(report.getCategories().stream().anyMatch(c ->
                "audio".equals(c.getCategory()) && "fail".equals(c.getStatus())));
    }

    @Test
    void allowsSilentDeliveryWhenLenientConfigured() {
        props.setQcAllowSilentAudio(true);
        FfmpegTool.MediaInfo info = goodInfo();
        info.setHasAudio(false);
        info.setAudioDuration(0);

        DeliveryQc report = service.assess(new MixPlanner.Plan(), new MixParams(), 90,
                info, goodAudio(), goodVideo(), true, false, false, 0);

        assertEquals("warn", report.getStatus());
    }

    @Test
    void failsOnExtremelyLowMeasuredLoudness() {
        FfmpegTool.AudioQuality audio = goodAudio();
        audio.setMeanVolumeDb(-72.0);

        DeliveryQc report = service.assess(planWithOneSegment(), new MixParams(), 90,
                goodInfo(), audio, goodVideo(), true, false, false, 0);

        assertEquals("fail", report.getStatus());
        assertTrue(report.getCategories().stream().flatMap(category -> category.getIssues().stream())
                .anyMatch(issue -> issue.contains("平均响度")));
    }

    @Test
    void failsOnExcessiveSilence() {
        FfmpegTool.AudioQuality audio = goodAudio();
        audio.setMaxSilenceSec(5.0);

        DeliveryQc report = service.assess(planWithOneSegment(), new MixParams(), 90,
                goodInfo(), audio, goodVideo(), true, false, false, 0);

        assertEquals("fail", report.getStatus());
    }

    @Test
    void failsOnSolidRedMagentaErrorFrames() {
        FfmpegTool.VideoQuality video = goodVideo();
        video.setRedMagentaSec(3.0);

        DeliveryQc report = service.assess(planWithOneSegment(), new MixParams(), 90,
                goodInfo(), goodAudio(), video, true, false, false, 0);

        assertEquals("fail", report.getStatus());
        assertTrue(report.getCategories().stream().anyMatch(c ->
                "video".equals(c.getCategory()) && "fail".equals(c.getStatus())));
        assertTrue(report.getCategories().stream()
                .filter(c -> "video".equals(c.getCategory()))
                .flatMap(c -> c.getIssues().stream())
                .anyMatch(text -> text.contains("纯红/品红")));
    }

    @Test
    void warnsOnSemanticGridFallback() {
        MixPlanner.Plan plan = planWithOneSegment();
        plan.setSemanticSegmentCount(1);
        plan.setGridFallbackCount(2);

        DeliveryQc report = service.assess(plan, new MixParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);

        assertEquals("warn", report.getStatus());
        assertTrue(report.getCategories().stream().anyMatch(c ->
                "semantic".equals(c.getCategory()) && "warn".equals(c.getStatus())));
    }

    @Test
    void warnsOnDuplicateSegments() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        MixPlanner.Segment s1 = new MixPlanner.Segment();
        s1.setMaterialId(1L);
        s1.setSourceStart(0);
        s1.setDuration(3);
        s1.setSourceDuration(15);
        MixPlanner.Segment s2 = new MixPlanner.Segment();
        s2.setMaterialId(1L);
        s2.setSourceStart(0);
        s2.setDuration(3);
        s2.setSourceDuration(15);
        plan.getSegments().add(s1);
        plan.getSegments().add(s2);

        DeliveryQc report = service.assess(plan, new MixParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);

        assertTrue(report.getCategories().stream().anyMatch(c ->
                "duplicate".equals(c.getCategory()) && "warn".equals(c.getStatus())));
    }
    @Test
    void warnsWhenProductExposureTooShort() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        MixPlanner.Segment product = new MixPlanner.Segment();
        product.setMaterialId(2L);
        product.setSourceStart(0);
        product.setDuration(0.5);
        product.setSourceDuration(15);
        product.setSlot("product");
        MixPlanner.Segment body = new MixPlanner.Segment();
        body.setMaterialId(1L);
        body.setSourceStart(0);
        body.setDuration(10);
        body.setSourceDuration(15);
        body.setSlot("body");
        plan.getSegments().add(body);
        plan.getSegments().add(product);

        DeliveryQc report = service.assess(plan, new MixParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);

        assertTrue(report.getCategories().stream().anyMatch(c ->
                "exposure".equals(c.getCategory()) && "warn".equals(c.getStatus())));
    }

    @Test
    void warnsOnFragmentedRhythm() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        for (int i = 0; i < 5; i++) {
            MixPlanner.Segment s = new MixPlanner.Segment();
            s.setMaterialId((long) (i + 1));
            s.setSourceStart(0);
            s.setDuration(0.5);
            s.setSourceDuration(15);
            s.setSlot("body");
            plan.getSegments().add(s);
        }

        DeliveryQc report = service.assess(plan, new MixParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);

        assertTrue(report.getCategories().stream().anyMatch(c ->
                "rhythm".equals(c.getCategory()) && "warn".equals(c.getStatus())));
    }

    @Test
    void warnsWhenSubtitleCueOutOfRange() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        MixPlanner.Segment body = new MixPlanner.Segment();
        body.setMaterialId(1L);
        body.setSourceStart(0);
        body.setDuration(3);
        body.setSourceDuration(15);
        body.setSlot("body");
        plan.getSegments().add(body);
        MixPlanner.Plan.CaptionCue cue = new MixPlanner.Plan.CaptionCue();
        cue.setStart(95);
        cue.setEnd(100);
        cue.setText("超出成片");
        plan.getNarrationCaptions().add(cue);

        DeliveryQc report = service.assess(plan, new MixParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);

        assertTrue(report.getCategories().stream().anyMatch(c ->
                "subtitleSync".equals(c.getCategory()) && "warn".equals(c.getStatus())));
    }

    // ---------------- strict-delivery 升级 ----------------

    @Test
    void strictDeliveryDefaultsToCompatibilitySafeWarnBehavior() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        MixPlanner.Segment s1 = new MixPlanner.Segment();
        s1.setMaterialId(1L);
        s1.setSourceStart(0);
        s1.setDuration(3);
        s1.setSourceDuration(15);
        s1.setSlot("body");
        MixPlanner.Segment s2 = new MixPlanner.Segment();
        s2.setMaterialId(1L);
        s2.setSourceStart(0);
        s2.setDuration(3);
        s2.setSourceDuration(15);
        s2.setSlot("body");
        plan.getSegments().add(s1);
        plan.getSegments().add(s2);

        DeliveryQc report = service.assess(plan, new MixParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);

        assertEquals("warn", report.getStatus(), "未显式开启严格交付时重复风险必须保持提示，不拦截");
        assertTrue(report.getCategories().stream().anyMatch(c ->
                "duplicate".equals(c.getCategory()) && "warn".equals(c.getStatus())));
    }

    @Test
    void strictDeliveryBlocksPlanWithOnlyGridFallbackVisuals() {
        MixPlanner.Plan plan = planWithOneSegment();
        plan.setSemanticSegmentCount(0);
        plan.setGridFallbackCount(3);

        DeliveryQc report = service.assess(plan, strictParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);

        assertEquals("fail", report.getStatus());
        assertTrue(report.getCategories().stream().anyMatch(c ->
                "semantic".equals(c.getCategory()) && "fail".equals(c.getStatus())));
    }

    @Test
    void strictDeliveryPromotesHookDuplicateSubtitleSyncWarningsToHardFail() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        // duplicate：完全重复的一对片段
        MixPlanner.Segment s1 = new MixPlanner.Segment();
        s1.setMaterialId(1L);
        s1.setSourceStart(0);
        s1.setDuration(3);
        s1.setSourceDuration(15);
        s1.setSlot("body");
        MixPlanner.Segment s2 = new MixPlanner.Segment();
        s2.setMaterialId(1L);
        s2.setSourceStart(0);
        s2.setDuration(3);
        s2.setSourceDuration(15);
        s2.setSlot("body");
        plan.getSegments().add(s1);
        plan.getSegments().add(s2);
        // hook：缺钩子文案
        plan.setHookStrategy("CONFLICT");
        // subtitleSync：字幕越界
        MixPlanner.Plan.CaptionCue cue = new MixPlanner.Plan.CaptionCue();
        cue.setStart(95);
        cue.setEnd(100);
        cue.setText("超出成片");
        plan.getNarrationCaptions().add(cue);

        MixParams strict = new MixParams();
        strict.setStrictDelivery(true);
        DeliveryQc report = service.assess(plan, strict, 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);

        assertEquals("fail", report.getStatus(), "严格交付下三条提示性维度必须升级为硬拦截");
        assertTrue(report.getCategories().stream().anyMatch(c ->
                "hook".equals(c.getCategory()) && "fail".equals(c.getStatus())));
        assertTrue(report.getCategories().stream().anyMatch(c ->
                "duplicate".equals(c.getCategory()) && "fail".equals(c.getStatus())));
        assertTrue(report.getCategories().stream().anyMatch(c ->
                "subtitleSync".equals(c.getCategory()) && "fail".equals(c.getStatus())));
        assertTrue(report.getSummary().contains("拦截"));
    }

    @Test
    void strictDeliveryPromotesOnlyAffectedCategories() {
        // 仅钩子文案缺失：只有 hook 升级，duplicate/subtitleSync 不受影响
        MixPlanner.Plan hookOnly = planWithOneSegment();
        hookOnly.setHookText(null);
        DeliveryQc hookReport = service.assess(hookOnly, strictParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);
        assertTrue(hookReport.getCategories().stream().anyMatch(c ->
                "hook".equals(c.getCategory()) && "fail".equals(c.getStatus())));
        assertTrue(hookReport.getCategories().stream().anyMatch(c ->
                "duplicate".equals(c.getCategory()) && "pass".equals(c.getStatus())));
        assertTrue(hookReport.getCategories().stream().anyMatch(c ->
                "subtitleSync".equals(c.getCategory()) && "pass".equals(c.getStatus())));

        // 仅字幕越界：只有 subtitleSync 升级
        MixPlanner.Plan syncOnly = planWithOneSegment();
        MixPlanner.Plan.CaptionCue cue = new MixPlanner.Plan.CaptionCue();
        cue.setStart(95);
        cue.setEnd(100);
        cue.setText("超出成片");
        syncOnly.getNarrationCaptions().add(cue);
        DeliveryQc syncReport = service.assess(syncOnly, strictParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, false, false, 0);
        assertTrue(syncReport.getCategories().stream().anyMatch(c ->
                "subtitleSync".equals(c.getCategory()) && "fail".equals(c.getStatus())));
        assertTrue(syncReport.getCategories().stream().anyMatch(c ->
                "hook".equals(c.getCategory()) && "pass".equals(c.getStatus())));
        assertTrue(syncReport.getCategories().stream().anyMatch(c ->
                "duplicate".equals(c.getCategory()) && "pass".equals(c.getStatus())));
    }

    @Test
    void strictDeliveryLeavesCleanDeliveryPassing() {
        DeliveryQc report = service.assess(richPlan(), strictParams(), 90,
                goodInfo(), goodAudio(), goodVideo(), true, true, true, 5);

        assertEquals("pass", report.getStatus(), "严格交付只升级受影响维度，干净成片仍应通过");
    }

    private MixParams strictParams() {
        MixParams params = new MixParams();
        params.setStrictDelivery(true);
        return params;
    }

}
