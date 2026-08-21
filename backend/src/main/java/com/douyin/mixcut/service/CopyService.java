package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.HookStrategy;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.UseCase;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 文案层：钩子 / 标题 / 口播脚本 / 标签。
 *
 * 流量视角的取舍写在 prompt 里：
 * 钩子只有前 3 秒，抖音完播率的生死线。所以约束死——12 字以内、口语、有冲突或利益点、
 * 不要形容词堆砌、不要"家人们"这种被打烂的开场。
 * AI 挂了也不能让出片断掉，所以每个方法都带本地兜底词库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopyService {

    private final AiService ai;

    private static final List<String> FALLBACK_HOOKS = List.of(
            "这个我用了三个月才敢说",
            "别再花冤枉钱了",
            "99%的人第一步就做错了",
            "我把柜子里的都扔了只留这瓶",
            "美容院不会告诉你的事",
            "便宜到我以为是假货",
            "用之前和用之后差太多",
            "被问了一百次的那个",
            "这步做对了皮肤直接改命",
            "熬夜党救命一支"
    );

    /** 生成钩子文案 */
    public String hook(Project project, String extra) {
        return hook(project, extra, null);
    }

    /** 生成钩子文案，可指定流量策略。 */
    public String hook(Project project, String extra, HookStrategy strategy) {
        String sys = """
                你是有十年实战经验的抖音带货剪辑总监，服务的是可直接交付市场的美妆、护肤、食品和日用带货账号。
                钩子的唯一目标：让用户在前 3 秒不划走，并自然进入产品利益点。
                硬性要求：
                1. 不超过 14 个字，中文口语，像真人面对镜头在说；
                2. 必须有冲突感、反常识、强利益点或强好奇缺口，四选一；
                3. 禁止"家人们""废话不多说""今天给大家分享"这类被用烂的开场；
                4. 禁止堆形容词，禁止感叹号连用，禁止 emoji；
                5. 不得出现绝对化用语、医疗功效承诺或无法证明的成交承诺；
                6. 钩子要能被自然口播，不写画面说明、镜头编号或生硬营销腔。
                只输出这一句钩子，不要引号，不要解释。
                """ + strategyLine(strategy);
        StringBuilder u = new StringBuilder();
        if (project != null) {
            append(u, "品牌", project.getBrand());
            append(u, "品类", project.getCategory());
            append(u, "产品", project.getProduct());
            append(u, "卖点", project.getSellingPoints());
            append(u, "目标人群", project.getAudience());
            append(u, "语气", project.getTone());
            append(u, "禁用词", project.getBannedWords());
            append(u, "补充要求", project.getExtraPrompt());
        }
        append(u, "本次画面/素材证据", extra);
        if (u.length() == 0) u.append("未提供项目或可验证素材；只使用保守提问式开场，不虚构品类、产品、功效或优惠");

        AiService.Answer a = ai.ask(UseCase.hook, sys, u.toString(), 1.0, 120,
                project == null ? null : project.getRouteOverrides());
        if (a.ok() && a.text() != null && !a.text().isBlank()) {
            return clean(a.text(), 20);
        }
        log.warn("hook 生成失败，用兜底词库: {}", a.error());
        return FALLBACK_HOOKS.get(new Random().nextInt(FALLBACK_HOOKS.size()));
    }

    /** 一次生成 n 条不重复的钩子（批量出片时每条一个） */
    public List<String> hooks(Project project, int n, String extra) {
        List<String> out = new ArrayList<>();
        if (n <= 0) return out;
        String sys = """
                你是抖音爆款短视频的开场钩子撰稿人。
                规则：每条不超过 14 字、口语、有冲突/反常识/强利益点；
                禁止"家人们""今天给大家分享"；禁止 emoji；禁止绝对化用语。
                彼此之间角度必须不同，不要换词式的重复。
                """;
        StringBuilder u = new StringBuilder("请生成 " + n + " 条钩子。\n");
        if (project != null) {
            append(u, "品牌", project.getBrand());
            append(u, "品类", project.getCategory());
            append(u, "产品", project.getProduct());
            append(u, "卖点", project.getSellingPoints());
            append(u, "目标人群", project.getAudience());
            append(u, "禁用词", project.getBannedWords());
        }
        append(u, "本次画面/素材证据", extra);
        if (project == null && (extra == null || extra.isBlank())) {
            u.append("\n未提供项目或可验证素材：只使用保守提问式开场，不虚构品类、产品、功效或优惠。");
        }
        u.append("\n输出 JSON 数组，形如 [\"钩子1\",\"钩子2\"]");

        JsonNode arr = ai.askJson(UseCase.hook, sys, u.toString(), 1.05, 800,
                project == null ? null : project.getRouteOverrides());
        if (arr != null && arr.isArray()) {
            for (JsonNode x : arr) {
                String s = clean(x.asText(""), 20);
                if (!s.isBlank() && !out.contains(s)) out.add(s);
            }
        }
        // 不够就补兜底
        List<String> pool = new ArrayList<>(FALLBACK_HOOKS);
        java.util.Collections.shuffle(pool);
        for (String candidate : pool) {
            if (out.size() >= n) break;
            if (!out.contains(candidate)) out.add(candidate);
        }
        if (out.size() < n) {
            throw new IllegalStateException("无法生成 " + n + " 条不重复钩子；当前仅得到 " + out.size()
                    + " 条，已拒绝循环复用，请缩小批量或补充更多卖点");
        }
        return out;
    }

    /** 一次生成 n 条不重复的钩子；可传入与每条对应的策略保证角度互不相同。 */
    public List<String> hooks(Project project, int n, String extra, List<HookStrategy> strategies) {
        if (strategies == null || strategies.isEmpty()) return hooks(project, n, extra);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            HookStrategy strategy = strategies.get(i % strategies.size());
            String hook = hook(project, extra, strategy);
            if (!hook.isBlank() && !out.contains(hook)) out.add(hook);
        }
        // 不够就补兜底
        List<String> pool = new ArrayList<>(FALLBACK_HOOKS);
        java.util.Collections.shuffle(pool);
        for (String candidate : pool) {
            if (out.size() >= n) break;
            if (!out.contains(candidate)) out.add(candidate);
        }
        if (out.size() < n) {
            throw new IllegalStateException("无法生成 " + n + " 条不重复钩子；当前仅得到 " + out.size()
                    + " 条，已拒绝循环复用，请缩小批量或补充更多卖点");
        }
        return out;
    }

    /** 中段再钩子文案：承接开头钩子，在片中段重新拉住完播。 */
    public String rehook(Project project, String extra, HookStrategy strategy) {
        String sys = """
                你是有十年实战经验的抖音带货剪辑总监。
                现在要为一条已经放了一段时间的带货视频，在"中段"补一句再钩子，把要走的人重新拉住看完。
                硬性要求：
                1. 不超过 16 个字，中文口语，承接但不重复开头钩子；
                2. 制造"后面有反转/结果/利益点"的期待，不剧透关键信息；
                3. 禁止"家人们""废话不多说"；禁止 emoji；禁止绝对化用语和功效承诺。
                只输出这一句再钩子，不要引号，不要解释。
                """ + strategyLine(strategy);
        StringBuilder u = new StringBuilder();
        if (project != null) {
            append(u, "品牌", project.getBrand());
            append(u, "产品", project.getProduct());
            append(u, "卖点", project.getSellingPoints());
            append(u, "人群", project.getAudience());
            append(u, "语气", project.getTone());
            append(u, "禁用词", project.getBannedWords());
        }
        append(u, "本次画面/素材证据", extra);
        if (u.length() == 0) u.append("未提供项目或可验证素材；用保守衔接式表达，不虚构品类、产品、功效或优惠");

        AiService.Answer a = ai.ask(UseCase.hook, sys, u.toString(), 1.0, 120,
                project == null ? null : project.getRouteOverrides());
        if (a.ok() && a.text() != null && !a.text().isBlank()) {
            return clean(a.text(), 20);
        }
        return "看到最后的都赚到了";
    }

    private String strategyLine(HookStrategy strategy) {
        if (strategy == null) return "";
        return switch (strategy) {
            case CONFLICT -> "本次钩子角度：制造冲突/对立/争议。";
            case RESULT -> "本次钩子角度：先给结果/效果，再倒叙原因。";
            case SUSPENSE -> "本次钩子角度：埋悬念，逼用户看完。";
            case REWARD -> "本次钩子角度：直接抛利益点/省钱/奖励。";
            case COUNTERINTUITIVE -> "本次钩子角度：推翻大众认知的反常识。";
            case QUESTION -> "本次钩子角度：用提问对号入座。";
            case VISUAL_IMPACT -> "本次钩子角度：用画面/前后对比制造视觉冲击。";
        };
    }

    /** 口播脚本（给配音/字幕用） */
    public String script(Project project, int seconds, String extra) {
        return script(project, seconds, extra, null, null, 0);
    }

    public String script(Project project, int seconds, String extra, String hookText,
                         HookStrategy strategy, int variantIndex) {
        return script(project, seconds, extra, hookText, strategy, variantIndex, null);
    }

    public String script(Project project, int seconds, String extra, String hookText,
                         HookStrategy strategy, int variantIndex, String materialSummary) {
        String sys = """
                你是有十年实战经验的抖音带货口播编导。写一段可以直接由自然人声朗读、可直接交付发布的中文口播稿。
                要求：口语化、短句、每句不超过 18 字；开头承接钩子但不要逐字重复钩子，中段按痛点、体验、卖点、使用场景递进，结尾带克制自然的行动引导。
                语速要适合真人配音，句子之间保留自然停顿和情绪起伏；每 2-3 句换一个表达角度，避免机械重复卖点。
                不要写镜头说明，不要写分镜编号，不要 markdown，只输出可朗读的正文。
                不得出现绝对化用语、医疗功效承诺或无法证明的成交承诺。
                """ + strategyLine(strategy);
        StringBuilder u = new StringBuilder("时长约 " + seconds + " 秒（按每秒 4.2 字估算字数，宁可略短也不要拖腔）。\n");
        if (project != null) {
            append(u, "品牌", project.getBrand());
            append(u, "产品", project.getProduct());
            append(u, "卖点", project.getSellingPoints());
            append(u, "人群", project.getAudience());
            append(u, "语气", project.getTone());
            append(u, "禁用词", project.getBannedWords());
            append(u, "补充要求", project.getExtraPrompt());
        }
        append(u, "当前开场钩子", hookText);
        append(u, "本次画面/素材内容（口播必须贴合这些画面内容来讲，画面出现什么就讲什么，禁止讲与画面无关的内容）", materialSummary);
        append(u, "本次变体编号", variantIndex <= 0 ? null : String.valueOf(variantIndex));
        append(u, "补充", extra);

        AiService.Answer a = ai.ask(UseCase.script, sys, u.toString(), 0.9,
                Math.max(400, seconds * 20), project == null ? null : project.getRouteOverrides());
        return a.ok() ? naturalizeSpeech(a.text()) : "";
    }

    /** 标题 + 话题标签 */
    public List<String> titles(Project project, int n) {
        String sys = "你是抖音标题优化师。输出 JSON 数组，每个元素是一条不超过 20 字的标题，" +
                "自带 2-3 个 #话题标签，风格口语、有钩子、不夸大。";
        StringBuilder u = new StringBuilder("生成 " + n + " 条。\n");
        if (project != null) {
            append(u, "品牌", project.getBrand());
            append(u, "产品", project.getProduct());
            append(u, "卖点", project.getSellingPoints());
            append(u, "人群", project.getAudience());
        }
        JsonNode arr = ai.askJson(UseCase.titles, sys, u.toString(), 1.0, 700,
                project == null ? null : project.getRouteOverrides());
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode x : arr) {
                String s = clean(x.asText(""), 60);
                if (!s.isBlank()) out.add(s);
            }
        }
        return out;
    }

    private String naturalizeSpeech(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceAll("(?m)^\\s*[-*\\d.、)]+\\s*", "")
                .replaceAll("[;；]", "，")
                .replaceAll("([。！？!?])\\s+", "$1\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private void append(StringBuilder sb, String k, String v) {
        if (v != null && !v.isBlank()) sb.append(k).append("：").append(v.trim()).append('\n');
    }

    private String clean(String s, int max) {
        if (s == null) return "";
        String r = s.trim()
                .replaceAll("^[\"'“”‘’\\s]+", "")
                .replaceAll("[\"'“”‘’\\s]+$", "")
                .replaceAll("^\\d+[.、)]\\s*", "")
                .replace("\n", " ")
                .trim();
        if (r.length() > max) r = r.substring(0, max);
        return r;
    }
}
