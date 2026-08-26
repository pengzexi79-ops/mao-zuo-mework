package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialAnalysis;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.MaterialSegment;
import com.douyin.mixcut.domain.Project;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic, provider-neutral semantic evidence matching. No external AI or filesystem access. */
public final class SemanticEvidenceMatcher {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private SemanticEvidenceMatcher() { }

    public enum Status { matched, fallback, missing, blocker }

    public record Evidence(Status status, Long materialId, Long segmentId, Integer idx,
                            Double startSec, Double endSec, String slot, List<String> evidence,
                            String reason, double confidence) {
        public Evidence {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record Result(List<Evidence> matched, List<Evidence> fallback, List<Evidence> missing,
                         List<Evidence> blockers) {
        public Result {
            matched = List.copyOf(matched == null ? List.of() : matched);
            fallback = List.copyOf(fallback == null ? List.of() : fallback);
            missing = List.copyOf(missing == null ? List.of() : missing);
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
        }
        public int semanticSegmentCount() { return matched.size(); }
    }

    /** Matches supplied candidates without reading repositories. */
    public static Result match(Project project, List<Material> materials,
                               Map<Long, MaterialAnalysis> analyses,
                               Map<Long, List<MaterialSegment>> segmentsByMaterial,
                               String slot, boolean strictDelivery) {
        List<Evidence> matched = new ArrayList<>(), fallback = new ArrayList<>(), missing = new ArrayList<>(), blockers = new ArrayList<>();
        List<Material> candidates = materials == null ? List.of() : materials.stream().filter(Objects::nonNull).toList();
        if (candidates.isEmpty()) {
            missing.add(new Evidence(Status.missing, null, null, null, null, null, slot,
                    List.of(), "没有可用候选素材", 0));
            return new Result(matched, fallback, missing, blockers);
        }
        Set<String> projectTokens = tokens(projectText(project));
        for (Material material : candidates) {
            if (!usable(material)) {
                fallback.add(base(Status.fallback, material, slot, "素材未 ready 或质量/时长不可用"));
                continue;
            }
            MaterialAnalysis analysis = analyses == null ? null : analyses.get(material.getId());
            if (analysis == null) {
                fallback.add(base(Status.fallback, material, slot, "缺少分析数据，保留 role/时长网格回退"));
                continue;
            }
            if (!"completed".equalsIgnoreCase(analysis.getStatus())) {
                fallback.add(base(Status.fallback, material, slot, "分析状态为 " + safe(analysis.getStatus()) + "，不是 completed"));
                continue;
            }
            if (!"scene".equalsIgnoreCase(analysis.getSource())) {
                fallback.add(base(Status.fallback, material, slot, "分析来源为 " + safe(analysis.getSource()) + "，不是 scene"));
                continue;
            }
            List<MaterialSegment> segments = segmentsByMaterial == null ? List.of() : segmentsByMaterial.getOrDefault(material.getId(), List.of());
            boolean anyValid = false;
            for (MaterialSegment segment : segments) {
                if (!valid(segment, material)) continue;
                anyValid = true;
                String structuredText = safe(analysis.getTagsJson()) + " " + safe(analysis.getSummary()) + " " + safe(analysis.getOcrTextsJson());
                Set<String> materialTokens = tokens(structuredText + " " + safe(material.getTags()));
                boolean textHit = !projectTokens.isEmpty() && !Collections.disjoint(projectTokens, materialTokens);
                boolean roleHit = roleMatches(material.getRole(), slot);
                if (textHit || roleHit) {
                    List<String> evidence = new ArrayList<>();
                    if (textHit) evidence.add("project_text_material_tags");
                    if (roleHit) evidence.add("material_role:" + safe(material.getRole() == null ? null : material.getRole().name()));
                    matched.add(new Evidence(Status.matched, material.getId(), segment.getId(), segment.getIdx(), segment.getStartSec(), segment.getEndSec(), slot, evidence,
                            "completed scene 分析且文本/role 命中", textHit && roleHit ? 0.95 : 0.8));
                }
            }
            if (!anyValid) fallback.add(base(Status.fallback, material, slot, "分析存在但没有合法时间段"));
        }
        if (matched.isEmpty() && !candidates.isEmpty()) {
            missing.add(new Evidence(Status.missing, null, null, null, null, null, slot,
                    List.of(), "没有候选同时满足 completed+scene、合法时间段和文本/role 证据", 0));
            boolean confirmedSceneAnalysis = candidates.stream().anyMatch(material -> {
                MaterialAnalysis candidate = analyses == null ? null : analyses.get(material.getId());
                return usable(material) && candidate != null
                        && "completed".equalsIgnoreCase(candidate.getStatus())
                        && "scene".equalsIgnoreCase(candidate.getSource());
            });
            if (strictDelivery && confirmedSceneAnalysis) {
                blockers.add(new Evidence(Status.blocker, null, null, null, null, null, slot,
                        List.of(), "严格交付下存在可用 completed+scene 素材但没有确实的 scene 语义命中", 0));
            }
        }
        return new Result(matched, fallback, missing, blockers);
    }

    private static Evidence base(Status status, Material m, String slot, String reason) {
        return new Evidence(status, m.getId(), null, null, null, null, slot, List.of(), reason, 0);
    }
    private static boolean usable(Material m) {
        return m.getStatus() == Material.Status.ready && m.getFilePath() != null && !m.getFilePath().isBlank()
                && m.getFileType() == Material.FileType.video && m.getDurationSec() != null && m.getDurationSec() >= 1.0;
    }
    private static boolean valid(MaterialSegment s, Material m) {
        if (s == null || s.getStartSec() == null || s.getEndSec() == null) return false;
        double start = s.getStartSec(), end = s.getEndSec();
        double duration = m.getDurationSec() == null ? 0 : m.getDurationSec();
        return Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end > start && end <= duration + 0.05;
    }
    private static boolean roleMatches(MaterialRole role, String slot) {
        if (role == null || slot == null) return false;
        return switch (slot.toLowerCase(Locale.ROOT)) {
            case "hook" -> role == MaterialRole.hook;
            case "product" -> role == MaterialRole.product;
            case "celebrity" -> role == MaterialRole.celebrity;
            case "endcard" -> role == MaterialRole.endcard;
            case "body" -> role == MaterialRole.body;
            default -> false;
        };
    }
    private static String projectText(Project p) {
        if (p == null) return "";
        return String.join(" ", safe(p.getName()), safe(p.getBrand()), safe(p.getCategory()), safe(p.getProduct()), safe(p.getSellingPoints()), safe(p.getAudience()), safe(p.getTone()), safe(p.getExtraPrompt()));
    }
    private static Set<String> tokens(String value) {
        Set<String> out = new HashSet<>();
        if (value == null) return out;
        var matcher = TOKEN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) out.add(matcher.group());
        return out;
    }
    private static String safe(Object value) { return value == null ? "" : String.valueOf(value); }
}
