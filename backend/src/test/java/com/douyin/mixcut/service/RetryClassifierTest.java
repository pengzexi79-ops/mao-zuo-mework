package com.douyin.mixcut.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryClassifierTest {

    @Test
    void hardFailuresAreNeverRetried() {
        assertEquals(RetryClassifier.Outcome.HARD, RetryClassifier.classify("素材库中没有可用的画面素材（视频/图片）"));
        assertEquals(RetryClassifier.Outcome.HARD, RetryClassifier.classify("没有可覆盖全片的音轨：请指定 BGM"));
        assertEquals(RetryClassifier.Outcome.HARD, RetryClassifier.classify("所有片段切片均失败，请检查素材是否损坏"));
        assertEquals(RetryClassifier.Outcome.HARD, RetryClassifier.classify("找不到 ffmpeg。请安装"));
        assertEquals(RetryClassifier.Outcome.HARD, RetryClassifier.classify("未授权访问该素材"));
        assertEquals(RetryClassifier.Outcome.HARD, RetryClassifier.classify("单条渲染超过时限"));
    }

    @Test
    void replaceableFailuresAreRetryable() {
        assertEquals(RetryClassifier.Outcome.RETRYABLE, RetryClassifier.classify("检测到与近期项目成片画面重叠，尝试切换素材变体"));
        assertEquals(RetryClassifier.Outcome.RETRYABLE, RetryClassifier.classify("有 2 段内容完全重复"));
        assertEquals(RetryClassifier.Outcome.RETRYABLE, RetryClassifier.classify("所选背景音乐不可用"));
    }

    @Test
    void unknownFailuresDefaultToHard() {
        assertEquals(RetryClassifier.Outcome.HARD, RetryClassifier.classify("渲染异常：NullPointerException: null"));
    }

    @Test
    void hardMarkerWinsOverRetryableSubstring() {
        assertEquals(RetryClassifier.Outcome.HARD, RetryClassifier.classify("没有可覆盖全片的音轨：钩子音频只覆盖开头"));
    }
}
