package com.douyin.mixcut.domain;

/** 工作流技能的两种形态。 */
public enum SkillType {
    builtin,    // 内置能力（如：取素材、拆条、混剪、生成钩子）
    ai,         // 由 LLM 以工具调用（function calling）方式驱动
    script      // 用户自定义的脚本/模板
}
