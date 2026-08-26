package com.douyin.mixcut.domain;

import java.util.Locale;

/**
 * 钩子策略：把"开场抓人"这一件事拆成可审计、可轮换的几种流量打法。
 *
 * <p>策略从项目语义（语气/品类/卖点）确定性推导出一个"基础策略"，再按 variant
 * 在全部策略间轮换，保证同一批成片里每条使用不同的钩子角度（unique strategy per job）。</p>
 */
public enum HookStrategy {

    /** 冲突 / 争议：制造对立或反差，快速拉起停留。 */
    CONFLICT("冲突"),
    /** 结果前置：先给结论/效果，再倒叙原因。 */
    RESULT("结果"),
    /** 悬念：埋一个未解开的钩子，逼用户看完。 */
    SUSPENSE("悬念"),
    /** 奖励 / 利益点：直接抛好处、省钱、免费。 */
    REWARD("奖励"),
    /** 反常识：推翻大众认知，制造认知缺口。 */
    COUNTERINTUITIVE("反常识"),
    /** 提问：用问题对号入座，触发自我代入。 */
    QUESTION("提问"),
    /** 视觉冲击：用画面/颜值/前后对比瞬间抓眼球。 */
    VISUAL_IMPACT("视觉冲击");

    private final String label;

    HookStrategy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 从项目语义推导基础策略；项目为空时回退到最稳的反常识打法。 */
    public static HookStrategy derive(Project project) {
        String text = textOf(project);
        if (containsAny(text, "测评", "评测", "成分", "理性", "数据", "对比")) return RESULT;
        if (containsAny(text, "冲突", "反转", "揭秘", "内幕", "翻车")) return CONFLICT;
        if (containsAny(text, "悬念", "剧情", "故事", "结局")) return SUSPENSE;
        if (containsAny(text, "种草", "好物", "福利", "省钱", "便宜", "优惠", "划算")) return REWARD;
        if (containsAny(text, "提问", "好奇", "为什么", "误区", "真相")) return QUESTION;
        if (containsAny(text, "视觉", "颜值", "美妆", "护肤", "效果", "前后")) return VISUAL_IMPACT;
        return COUNTERINTUITIVE;
    }

    /** 基础策略 + variant 轮换，保证同一批每条一个不同角度。 */
    public static HookStrategy select(Project project, int variant) {
        HookStrategy base = derive(project);
        HookStrategy[] all = values();
        int index = Math.floorMod(base.ordinal() + Math.max(0, variant), all.length);
        return all[index];
    }

    /** 安全解析，未知或空值返回 null，由调用方按 variant 兜底推导。 */
    public static HookStrategy safeValueOf(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private static String textOf(Project project) {
        if (project == null) return "";
        StringBuilder sb = new StringBuilder();
        append(sb, project.getTone());
        append(sb, project.getCategory());
        append(sb, project.getSellingPoints());
        append(sb, project.getBrand());
        append(sb, project.getProduct());
        append(sb, project.getAudience());
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static void append(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) sb.append(value).append(' ');
    }
}
