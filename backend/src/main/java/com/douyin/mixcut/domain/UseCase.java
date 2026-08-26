package com.douyin.mixcut.domain;

/** AI 能力的用途路由（决定用哪个供应商/模型生成什么）。 */
public enum UseCase {
    hook,       // 开头钩子文案
    script,     // 口播/脚本文案
    titles,     // 标题
    cta,        // 引导转化话术
    tag,        // 标签
    naming,     // 素材总包名称审核
    qc,         // 成片质检与修复建议
    general     // 通用对话/兜底
}
