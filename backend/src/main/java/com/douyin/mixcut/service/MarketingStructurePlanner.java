package com.douyin.mixcut.service;

import com.douyin.mixcut.dto.MixParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic marketing structure contract used by dry-run and rendering explanations. */
public final class MarketingStructurePlanner {
    private MarketingStructurePlanner() {
    }

    public record Stage(String code, String role, String objective, double targetSec,
                        List<String> allowedMaterialRoles, boolean allowRepeat,
                        boolean allowOriginalAudio, String narrationStrategy, String bgmStrategy) {
        public String label() {
            return code + " · " + role;
        }
    }

    public static List<Stage> stages(String rawPattern, double targetSec) {
        String pattern = normalizePattern(rawPattern);
        String[] codes = pattern.split("");
        double[] weights = weights(codes);
        double totalWeight = 0;
        for (double weight : weights) totalWeight += weight;
        List<Stage> result = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            String code = codes[i];
            double duration = round(Math.max(1.0, targetSec * weights[i] / totalWeight));
            result.add(stage(code, duration));
        }
        // Keep the displayed sum aligned with the requested duration after rounding.
        double delta = round(targetSec - result.stream().mapToDouble(Stage::targetSec).sum());
        if (!result.isEmpty() && Math.abs(delta) > 0.001) {
            Stage last = result.remove(result.size() - 1);
            result.add(new Stage(last.code(), last.role(), last.objective(), round(Math.max(1.0, last.targetSec() + delta)),
                    last.allowedMaterialRoles(), last.allowRepeat(), last.allowOriginalAudio(),
                    last.narrationStrategy(), last.bgmStrategy()));
        }
        return List.copyOf(result);
    }

    public static String normalizePattern(String rawPattern) {
        String raw = rawPattern == null ? "" : rawPattern.trim();
        if (raw.isBlank()) return "123234";
        String compact = raw.replaceAll("[^1-4]", "");
        if (compact.length() < 3 || compact.length() > 24) throw new IllegalArgumentException("营销结构序列只能包含 1-4，长度 3-24");
        return compact;
    }

    public static String preview(List<Stage> stages) {
        if (stages == null || stages.isEmpty()) return "";
        return stages.stream().map(stage -> stage.code() + "(" + format(stage.targetSec()) + "s)")
                .reduce((a, b) -> a + " → " + b).orElse("");
    }

    private static Stage stage(String code, double duration) {
        return switch (code) {
            case "1" -> new Stage("1", "钩子", "前三秒建立冲突、结果或强视觉吸引", duration,
                    List.of("hook", "body"), false, false, "hook-or-short-voice", "low-under-hook");
            case "2" -> new Stage("2", "讲解 / 痛点", "解释问题、卖点和用户为什么需要它", duration,
                    List.of("voice", "body", "celebrity"), false, true, "human-voice-first", "duck-under-voice");
            case "3" -> new Stage("3", "产品展示 / 证明", "展示产品、使用过程、效果或对比证据", duration,
                    List.of("product", "body", "celebrity"), false, true, "voice-or-original", "duck-under-voice");
            default -> new Stage("4", "成交收口 / CTA", "给出购买理由、优惠、品牌记忆和明确行动号召", duration,
                    List.of("endcard", "product", "body"), false, false, "cta-voice-or-text", "low-under-cta");
        };
    }

    private static double[] weights(String[] codes) {
        double[] weights = new double[codes.length];
        for (int i = 0; i < codes.length; i++) {
            weights[i] = switch (codes[i]) {
                case "1" -> 0.11;
                case "2" -> 0.22;
                case "3" -> 0.24;
                default -> 0.14;
            };
        }
        return weights;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
