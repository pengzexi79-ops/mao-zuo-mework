package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.dto.MixParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 严格文件夹顺序（strict ordered folders）计划加固回归测试：
 * 片头选择契约（fixed/rotate）、片头候选不足拦截与显式允许重复、
 * 片头时长计入步骤预算（不重复计算）、钩子时间窗与钩子音频在片头之后对齐、
 * 严格目录范围：绝不跨步骤 / 跨全库补位。
 */
class MixPlannerOrderedFoldersTest {

    private final MixPlanner planner = new MixPlanner();

    @Test
    void rotateIntroVariantsPickDifferentMaterialsAcrossVariants() {
        MixParams params = baseParams();
        params.setIntroEnabled(true);
        params.setIntroMode("rotate");
        params.setIntroDurationSec(3.0);
        List<MixPlanner.OrderedFolderStep> steps = List.of(
                new MixPlanner.OrderedFolderStep(1, "开头", List.of(
                        visual(1L, "intro-a.mp4", 8, MaterialRole.hook),
                        visual(2L, "intro-b.mp4", 8, MaterialRole.hook)), 10.0, true));

        MixPlanner.Plan v0 = planner.planOrderedFolders(steps, params, 0, "hook", null);
        MixPlanner.Plan v1 = planner.planOrderedFolders(steps, params, 1, "hook", null);

        assertTrue(v0.usable());
        assertTrue(v1.usable());
        assertEquals("intro", v0.getSegments().get(0).getSlot());
        assertEquals("intro", v1.getSegments().get(0).getSlot());
        assertNotEquals(v0.getSegments().get(0).getMaterialId(), v1.getSegments().get(0).getMaterialId(),
                "rotate 模式下不同批次应选择不同片头素材");
        assertTrue(v0.getNotes().stream().anyMatch(n -> n.contains("片头按批次轮换")));
        assertTrue(v1.getNotes().stream().anyMatch(n -> n.contains("片头按批次轮换")));
    }

    @Test
    void fixedIntroModeAppliesExplicitlySelectedIntroInOrderedPlan() {
        MixParams params = baseParams();
        params.setIntroEnabled(true);
        params.setIntroMode("fixed");
        params.setIntroMaterialId(2L);
        List<MixPlanner.OrderedFolderStep> steps = List.of(
                new MixPlanner.OrderedFolderStep(1, "开头", List.of(
                        visual(1L, "intro-a.mp4", 8, MaterialRole.hook),
                        visual(2L, "intro-b.mp4", 8, MaterialRole.hook)), 10.0, true));

        MixPlanner.Plan plan = planner.planOrderedFolders(steps, params, 0, "hook", null);

        assertTrue(plan.usable());
        assertEquals("intro", plan.getSegments().get(0).getSlot());
        assertEquals(2L, plan.getSegments().get(0).getMaterialId());
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("使用固定片头")));
    }

    @Test
    void insufficientIntroCandidatesAreBlockedUnlessRepeatExplicitlyAllowed() {
        List<MixPlanner.OrderedFolderStep> steps = List.of(
                new MixPlanner.OrderedFolderStep(1, "开头", List.of(
                        visual(1L, "only-intro.mp4", 8, MaterialRole.hook)), 10.0, true));

        MixParams blocked = baseParams();
        blocked.setIntroEnabled(true);
        blocked.setIntroMode("rotate");
        blocked.setIntroNoRepeat(true);
        blocked.setIntroAllowRepeatWhenInsufficient(false);
        MixPlanner.Plan plan = planner.planOrderedFolders(steps, blocked, 0, "hook", null);

        assertTrue(plan.usable(), "片头候选不足只应跳过片头，不应阻断步骤正文交付");
        assertTrue(plan.getSegments().stream().noneMatch(s -> "intro".equals(s.getSlot())),
                "未显式允许重复时，片头轮换候选不足必须被拦截（不落入时间线）");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("不重复保护")),
                "应保留不重复保护的审计说明");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("明确允许不足时重复")));

        MixParams allowed = baseParams();
        allowed.setIntroEnabled(true);
        allowed.setIntroMode("rotate");
        allowed.setIntroNoRepeat(true);
        allowed.setIntroAllowRepeatWhenInsufficient(true);
        MixPlanner.Plan repeated = planner.planOrderedFolders(steps, allowed, 0, "hook", null);

        assertTrue(repeated.usable());
        assertEquals("intro", repeated.getSegments().get(0).getSlot());
        assertEquals(1L, repeated.getSegments().get(0).getMaterialId());
        assertTrue(repeated.getNotes().stream().anyMatch(n -> n.contains("已按用户允许在不足时重复")));
    }

    @Test
    void introDurationIsCountedInsideStepBudgetNotAddedOnTop() {
        MixParams params = baseParams();
        params.setIntroEnabled(true);
        params.setIntroMode("rotate");
        params.setIntroDurationSec(3.0);
        List<MixPlanner.OrderedFolderStep> steps = List.of(
                new MixPlanner.OrderedFolderStep(1, "开头", List.of(
                        visual(1L, "intro-a.mp4", 8, MaterialRole.hook),
                        visual(2L, "intro-b.mp4", 8, MaterialRole.hook)), 10.0, true));

        MixPlanner.Plan plan = planner.planOrderedFolders(steps, params, 0, "hook", null);

        assertTrue(plan.usable());
        double introSec = plan.getSegments().stream().filter(s -> "intro".equals(s.getSlot()))
                .mapToDouble(MixPlanner.Segment::getDuration).sum();
        double hookSec = plan.getSegments().stream().filter(s -> "hook".equals(s.getSlot()))
                .mapToDouble(MixPlanner.Segment::getDuration).sum();
        assertEquals(3.0, introSec, 0.01);
        assertTrue(plan.getPlannedSec() <= 10.7,
                "片头时长必须计入步骤预算而不是叠加在目标之外（期望≈10s，实际 " + plan.getPlannedSec() + "s）");
        assertTrue(plan.getPlannedSec() >= 9.3,
                "步骤目标应基本达成（实际 " + plan.getPlannedSec() + "s）");
        assertTrue(Math.abs(introSec + hookSec - plan.getPlannedSec()) < 0.05,
                "时间线总长 = 片头 + 正文，不应重复计算（片头 " + introSec + "s + 正文 " + hookSec + "s vs 计划 " + plan.getPlannedSec() + "s）");
    }

    @Test
    void hookWindowAndHookAudioAlignAfterIntro() {
        MixParams params = baseParams();
        params.setIntroEnabled(true);
        params.setIntroMode("rotate");
        params.setIntroDurationSec(3.0);
        params.setHookAudioMaterialId(50L);
        List<MixPlanner.OrderedFolderStep> steps = List.of(
                new MixPlanner.OrderedFolderStep(1, "开头", List.of(
                        visual(1L, "intro-a.mp4", 8, MaterialRole.hook),
                        visual(2L, "intro-b.mp4", 8, MaterialRole.hook),
                        audio(49L, "voice.mp3", 30, MaterialRole.voice),
                        audio(50L, "hook-audio.mp3", 20, MaterialRole.bgm)), 10.0, true));

        MixPlanner.Plan plan = planner.planOrderedFolders(steps, params, 0, "hook", null);

        assertTrue(plan.usable());
        assertEquals(3.0, plan.getHookStartSec(), 0.01, "钩子时间窗必须从片头结束处开始，而不是 0");
        assertTrue(plan.getHookEndSec() > plan.getHookStartSec());
        assertEquals(50L, plan.getHookAudioMaterialId());
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("钩子声画已绑定")));
        assertTrue(plan.getHookAudioPath() != null && !plan.getHookAudioPath().isBlank());
    }

    @Test
    void requiredStepWithoutVisualsBlocksPlanWithoutCrossStepFill() {
        MixParams params = baseParams();
        List<MixPlanner.OrderedFolderStep> steps = List.of(
                new MixPlanner.OrderedFolderStep(1, "开头", List.of(
                        visual(1L, "body-a.mp4", 20, MaterialRole.body)), 8.0, true),
                new MixPlanner.OrderedFolderStep(2, "空目录", List.of(), 8.0, true));

        MixPlanner.Plan plan = planner.planOrderedFolders(steps, params, 0, "hook", null);

        assertFalse(plan.usable());
        assertTrue(plan.getSegments().isEmpty());
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("没有可读画面素材")));
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("不会跨步骤补位")));
    }

    @Test
    void nonRequiredEmptyStepIsSkippedAndRemainingStepsStillDeliver() {
        MixParams params = baseParams();
        List<MixPlanner.OrderedFolderStep> steps = List.of(
                new MixPlanner.OrderedFolderStep(1, "开头", List.of(
                        visual(1L, "body-a.mp4", 20, MaterialRole.body)), 8.0, true),
                new MixPlanner.OrderedFolderStep(2, "空目录", List.of(), 8.0, false),
                new MixPlanner.OrderedFolderStep(3, "结尾", List.of(
                        visual(3L, "body-c.mp4", 20, MaterialRole.body)), 8.0, true));

        MixPlanner.Plan plan = planner.planOrderedFolders(steps, params, 0, "hook", null);

        assertTrue(plan.usable(), "非必需空步骤应被跳过，其余步骤继续交付");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("不会跨步骤补位")));
        assertTrue(plan.getSegments().stream().noneMatch(s -> s.getMaterialId().equals(2L)),
                "跳过的空目录不能从其他目录或全库补入素材");
    }

    @Test
    void requiredStepCannotBeFilledFromOtherStepsMaterials() {
        MixParams params = baseParams();
        List<MixPlanner.OrderedFolderStep> steps = List.of(
                // 3s 单素材连一个切片窗口都榨不出多少：即使错位重榨也远低于 30s 目标
                new MixPlanner.OrderedFolderStep(1, "贫目录", List.of(
                        visual(1L, "short.mp4", 3, MaterialRole.body)), 30.0, true),
                new MixPlanner.OrderedFolderStep(2, "富目录", List.of(
                        visual(2L, "rich-a.mp4", 60, MaterialRole.body),
                        visual(3L, "rich-b.mp4", 60, MaterialRole.body)), 10.0, true));

        MixPlanner.Plan plan = planner.planOrderedFolders(steps, params, 0, "hook", null);

        assertFalse(plan.usable(), "第 1 步素材不足时不得用第 2 步素材跨步骤补位");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("不会跨目录补齐")));
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("仅生成")));
    }

    // ---------------- helpers ----------------

    private MixParams baseParams() {
        MixParams params = new MixParams();
        params.setMinSec(5);
        params.setMaxSec(30);
        params.setTargetSec(10);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        return params;
    }

    private Material visual(Long id, String name, double duration, MaterialRole role) {
        Material material = new Material();
        material.setId(id);
        material.setName(name);
        material.setFilePath("C:/fixtures/" + name);
        material.setDurationSec(duration);
        material.setRole(role);
        material.setFileType(Material.FileType.video);
        material.setStatus(Material.Status.ready);
        return material;
    }

    private Material audio(Long id, String name, double duration, MaterialRole role) {
        Material material = new Material();
        material.setId(id);
        material.setName(name);
        material.setFilePath("C:/fixtures/" + name);
        material.setDurationSec(duration);
        material.setRole(role);
        material.setFileType(Material.FileType.audio);
        material.setStatus(Material.Status.ready);
        return material;
    }
}
