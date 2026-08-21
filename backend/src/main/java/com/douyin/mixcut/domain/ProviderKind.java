package com.douyin.mixcut.domain;

/** AI 供应商协议类型。 */
public enum ProviderKind {
    openai,     // OpenAI 兼容接口（DeepSeek/Kimi/GLM/通义/火山方舟/OpenRouter/Ollama 等）
    anthropic,  // Anthropic 原生
    gemini      // Gemini 原生
}
