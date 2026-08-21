package com.douyin.mixcut.domain;

/** 素材在混剪中的角色标签（用于自动取材与编排）。 */
public enum MaterialRole {
    none,       // 未分类
    hook,       // 开头钩子（吸引停留）
    body,       // 实拍/种草片段（中间主体）
    product,    // 自有产品段
    celebrity,  // 明星/达人出镜
    voice,      // 人声音源（口播/配音）
    bgm,        // 背景音乐
    endcard     // 结尾转化卡片
}
