package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.EditorialBrief;
import com.douyin.mixcut.domain.HookStrategy;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.repository.EditorialBriefStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Derives the task-level editorial intent that drives audio selection.
 *
 * <p>The intent is a deterministic mapping from project semantics (tone/category/selling points)
 * to BGM mood keywords — no AI call, so it always works offline and always leaves the existing
 * least-used/random BGM fallback intact when nothing matches. Human voice priority and BGM
 * ducking are also recorded here for the render path.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditorialBriefService {

    private final EditorialBriefStore store;
    private final ObjectMapper om = new ObjectMapper();

    /** Project term fragment → preferred BGM mood keywords (matched against BGM name/tags). */
    private static final Map<String, List<String>> MOOD_HINTS = Map.ofEntries(
            Map.entry("测评", List.of("测评", "评测", "真实", "review", "testimonial")),
            Map.entry("理性", List.of("理性", "舒缓", "冷静", "minimal", "calm", "chill", "soft")),
            Map.entry("轻松", List.of("轻快", "轻松", "欢快", "upbeat", "happy", "bright", "fun")),
            Map.entry("种草", List.of("种草", "轻快", "欢快", "trendy", "pop", "bright")),
            Map.entry("美妆", List.of("美妆", "时尚", "beauty", "fashion", "glam", "chic")),
            Map.entry("护肤", List.of("护肤", "舒缓", "skincare", "calm", "soft", "clean")),
            Map.entry("食品", List.of("食品", "轻快", "食欲", "food", "appetizing", "bright", "warm")),
            Map.entry("数码", List.of("数码", "科技", "tech", "digital", "future", "electronic")),
            Map.entry("开箱", List.of("开箱", "科技", "unbox", "tech", "pop", "energetic"))
    );

    /**
     * Pure derivation of the audio intent from project semantics. Never blocks and never needs
     * an AI provider; returns a present=false intent when the project is null or has no hints.
     */
    public static MixPlanner.AudioIntent deriveIntent(Project project) {
        MixPlanner.AudioIntent intent = new MixPlanner.AudioIntent();
        if (project == null) {
            intent.setPresent(false);
            return intent;
        }
        String text = lower(join(project.getTone(), project.getCategory(), project.getSellingPoints(),
                project.getBrand(), project.getProduct()));
        Set<String> keywords = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> hint : MOOD_HINTS.entrySet()) {
            if (text.contains(hint.getKey())) keywords.addAll(hint.getValue());
        }
        intent.setMoodKeywords(new ArrayList<>(keywords));
        intent.setPreferHumanVoice(true);
        intent.setDuckBgm(true);
        intent.setPresent(!keywords.isEmpty());
        return intent;
    }

    /** 从项目语义推导任务级基础钩子策略（确定性，离线可用）。 */
    public static String deriveHookStrategy(Project project) {
        return HookStrategy.derive(project).name();
    }

    /** Persist the derived intent once per job (idempotent); returns the existing row on replay. */
    public EditorialBrief persistForJob(Long jobId, Project project) {
        if (jobId == null) return null;
        return store.findByJobId(jobId).orElseGet(() -> {
            MixPlanner.AudioIntent intent = deriveIntent(project);
            EditorialBrief brief = new EditorialBrief();
            brief.setJobId(jobId);
            brief.setProjectId(project == null ? null : project.getId());
            brief.setMoodKeywords(write(intent.getMoodKeywords()));
            brief.setHookStrategy(deriveHookStrategy(project));
            brief.setPreferHumanVoice(intent.isPreferHumanVoice());
            brief.setDuckBgm(intent.isDuckBgm());
            brief.setSummary(project == null ? null
                    : "语气=" + nz(project.getTone()) + "；品类=" + nz(project.getCategory()));
            return store.save(brief);
        });
    }

    public void deleteByJobId(Long jobId) {
        store.deleteByJobId(jobId);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String join(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) sb.append(value).append(' ');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private String write(List<String> keywords) {
        try {
            return om.writeValueAsString(keywords);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }
}
