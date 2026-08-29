package com.douyin.mixcut.domain;

/** AI 能力的用途路由（决定用哪个供应商/模型生成什么）。 */
public enum UseCase {
    hook,       // 开头钩子文案
    script,     // 口播/脚本文案
    plan,       // 镜头规划、工作流和分镜编排
    product,    // 产品卖点和电商项目策划
    titles,     // 标题
    cta,        // 引导转化话术
    tag,        // 标签
    naming,     // 素材总包名称审核
    qc,         // 成片质检与修复建议
    vision,     // 图片/视频视觉内容理解
    transcription, // 音频转写与时间轴理解
    translation, // 翻译与本地化
    rewrite,    // 文案改写与润色
    summarize,  // 内容摘要与结构化
    chat,       // AI 文本对话
    research,   // 信息整理与研究
    coding,     // 编程开发
    capability, // 受控能力调用与工作流编排
    image,      // 图片生成
    video,      // 视频生成
    voice,      // 语音生成
    general     // 通用对话/兜底
}
