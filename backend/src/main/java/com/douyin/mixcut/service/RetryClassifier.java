package com.douyin.mixcut.service;

import java.util.List;

/**
 * 出片失败分类器：把「可替换失败」和「硬失败」分开，决定是否值得自动重试。
 *
 * <p>只有能通过调整钩子策略 / BGM / 候选素材 / 切片变体修复的失败才允许重试；
 * 无法通过重规划修复的硬失败（媒体不可读、无音频、素材不足、未授权、超时、环境缺 ffmpeg）
 * 直接放弃重试，避免空转和浪费算力。</p>
 */
public final class RetryClassifier {

    public enum Outcome { RETRYABLE, HARD }

    /** 硬失败关键词：命中即不重试（顺序重要，硬失败优先于可替换关键词）。 */
    private static final List<String> HARD_MARKERS = List.of(
            "不可读取", "损坏", "无法读取", "无法解码", "无法探测", "所有片段切片均失败",
            "没有可覆盖全片的音轨", "没有可播放的音频", "没有可用音轨", "没有有效声音",
            "素材不足", "没有可用的画面素材", "切片池为空", "无法满足时长", "素材切片不足以",
            "找不到 ffmpeg", "未检测到 ffmpeg",
            "未授权", "unauthorized", "没有权限", "无权",
            "超过时限", "任务执行超过", "超时", "中止", "已停止",
            "剪辑计划未达到交付下限", "成品质检未通过");

    /** 可替换失败关键词：可通过切换钩子策略 / BGM / 候选素材 / 切片变体修复。 */
    private static final List<String> RETRYABLE_MARKERS = List.of(
            "重复", "重叠", "同源", "变体", "候选",
            "钩子", "BGM", "背景音乐", "背景声", "音轨不可用");

    private RetryClassifier() {
    }

    /** 任意一段信息命中硬失败关键词时判为 HARD；否则命中可替换关键词判为 RETRYABLE；默认 HARD。 */
    public static Outcome classify(String... messages) {
        if (messages == null) return Outcome.HARD;
        String text = String.join(" ", messages).toLowerCase();
        for (String marker : HARD_MARKERS) {
            if (text.contains(marker.toLowerCase())) return Outcome.HARD;
        }
        for (String marker : RETRYABLE_MARKERS) {
            if (text.contains(marker.toLowerCase())) return Outcome.RETRYABLE;
        }
        // 未知失败不冒进重试：宁可失败也要避免空转。
        return Outcome.HARD;
    }
}
