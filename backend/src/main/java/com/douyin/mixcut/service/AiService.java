package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.*;
import com.douyin.mixcut.external.AiClient;
import com.douyin.mixcut.repository.Repositories.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AI 聚合调度：一个用途（钩子/脚本/标题…）绑定一条"主供应商 + 兜底链"，
 * 主的挂了自动往下试，全挂了返回可读错误而不是抛异常炸掉出片流程。
 *
 * 这一层的价值在实际交付里非常具体：客户手上的 key 经常是中转站的，
 * 会限流、会掉线、会换模型名。没有兜底链，批量出片跑一半就断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final AiProviderRepo providerRepo;
    private final AiRouteRepo routeRepo;
    private final AiLogRepo logRepo;
    private final AiClient client;
    private final ObjectMapper om = new ObjectMapper();

    public record Answer(boolean ok, String text, String error, String providerName, String model) {
    }

    /** 是否已经配置了至少一个可用供应商 */
    public boolean ready() {
        return providerRepo.findByEnabledTrueOrderByPriorityAsc().stream()
                .anyMatch(this::hasConfiguredTextModel);
    }

    /**
     * 按用途调用 AI。routeOverrides 是项目级覆盖（JSON: {"hook": 3}），没有就用全局路由。
     */
    public Answer ask(UseCase useCase, String system, String user,
                      double temperature, int maxTokens, String routeOverridesJson) {
        List<Cand> chain = buildChain(useCase, routeOverridesJson);
        if (chain.isEmpty()) {
            return new Answer(false, null, "未配置任何可用的 AI 供应商，请先到「AI 接入」页添加", null, null);
        }
        StringBuilder errs = new StringBuilder();
        for (Cand c : chain) {
            AiClient.ChatResult r = client.chat(c.provider, c.model, system, user, temperature, maxTokens);
            writeLog(c, useCase, r);
            if (r.isOk()) {
                return new Answer(true, r.getText().trim(), null, c.provider.getName(), c.model);
            }
            errs.append("[").append(c.provider.getName()).append("/").append(c.model).append("] ")
                    .append(r.getErrorCode() == null ? "AI_REQUEST_FAILED" : r.getErrorCode())
                    .append(": ").append(r.getError()).append("; ");
            log.warn("AI provider {} failed for {}: {}", c.provider.getName(), useCase, r.getError());
        }
        return new Answer(false, null, "全部供应商均失败: " + errs, null, null);
    }

    public Answer ask(UseCase useCase, String system, String user) {
        return ask(useCase, system, user, 0.85, 900, null);
    }

    /** 让模型只返回 JSON；自动剥离 ```json 包裹并解析，解析失败返回 null */
    public JsonNode askJson(UseCase useCase, String system, String user,
                            double temperature, int maxTokens, String routeOverridesJson) {
        Answer a = ask(useCase, system + "\n\n严格只输出 JSON，不要任何解释文字、不要 markdown 代码块标记。",
                user, temperature, maxTokens, routeOverridesJson);
        if (!a.ok()) return null;
        return parseJsonLoose(a.text());
    }

    public JsonNode parseJsonLoose(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("```")) {
            int i = s.indexOf('\n');
            if (i > 0) s = s.substring(i + 1);
            int j = s.lastIndexOf("```");
            if (j > 0) s = s.substring(0, j);
            s = s.trim();
        }
        int start = -1, end = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{' || ch == '[') {
                start = i;
                break;
            }
        }
        for (int i = s.length() - 1; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch == '}' || ch == ']') {
                end = i;
                break;
            }
        }
        if (start < 0 || end <= start) return null;
        try {
            return om.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 连通性测试 */
    public Answer test(Long providerId, String model) {
        AiProvider p = providerRepo.findById(providerId).orElse(null);
        if (p == null) return new Answer(false, null, "供应商不存在", null, null);
        String m = (model == null || model.isBlank()) ? p.getDefaultModel() : model;
        if (m == null || m.isBlank()) return new Answer(false, null, "未指定模型", p.getName(), null);
        AiClient.ChatResult r = client.ping(p, m);
        Cand c = new Cand(p, m);
        writeLog(c, UseCase.general, r);
        return r.isOk()
                ? new Answer(true, r.getText(), null, p.getName(), m)
                : new Answer(false, null, r.getError(), p.getName(), m);
    }

    // ---------------- 内部 ----------------

    private record Cand(AiProvider provider, String model) {
    }

    private List<Cand> buildChain(UseCase useCase, String routeOverridesJson) {
        List<Cand> chain = new ArrayList<>();
        Map<Long, AiProvider> all = new LinkedHashMap<>();
        for (AiProvider p : providerRepo.findByEnabledTrueOrderByPriorityAsc()) {
            if (hasUsableCredential(p)) all.put(p.getId(), p);
        }
        if (all.isEmpty()) return chain;

        // 1) 项目级覆盖
        Long overrideId = null;
        if (routeOverridesJson != null && !routeOverridesJson.isBlank()) {
            try {
                JsonNode n = om.readTree(routeOverridesJson).path(useCase.name());
                if (n.isNumber()) overrideId = n.asLong();
                else if (n.isObject() && n.hasNonNull("providerId")) overrideId = n.get("providerId").asLong();
            } catch (Exception ignore) {
            }
        }
        if (overrideId != null && all.containsKey(overrideId)) {
            AiProvider p = all.get(overrideId);
            chain.add(new Cand(p, pickModel(p, null)));
        }

        // 2) 全局路由
        AiRoute route = routeRepo.findByUseCase(useCase.name()).orElse(null);
        if (route != null && route.getProviderId() != null && all.containsKey(route.getProviderId())) {
            AiProvider p = all.get(route.getProviderId());
            addUnique(chain, new Cand(p, pickModel(p, route.getModel())));
            if (route.getFallbacks() != null && !route.getFallbacks().isBlank()) {
                try {
                    for (JsonNode fb : om.readTree(route.getFallbacks())) {
                        Long fid = fb.isNumber() ? fb.asLong() : fb.path("providerId").asLong(0);
                        String fm = fb.isObject() ? fb.path("model").asText(null) : null;
                        if (fid != 0 && all.containsKey(fid)) {
                            AiProvider fp = all.get(fid);
                            addUnique(chain, new Cand(fp, pickModel(fp, fm)));
                        }
                    }
                } catch (Exception ignore) {
                }
            }
        }

        // 3) 兜底：所有启用的供应商按优先级排
        for (AiProvider p : all.values()) {
            addUnique(chain, new Cand(p, pickModel(p, null)));
        }
        return chain;
    }

    private void addUnique(List<Cand> chain, Cand c) {
        if (c.model == null || c.model.isBlank()) return;
        for (Cand e : chain) {
            if (Objects.equals(e.provider.getId(), c.provider.getId()) && Objects.equals(e.model, c.model)) return;
        }
        chain.add(c);
    }

    private String pickModel(AiProvider p, String prefer) {
        if (prefer != null && !prefer.isBlank()) return prefer;
        if (p.getDefaultModel() != null && !p.getDefaultModel().isBlank()) return p.getDefaultModel();
        if (p.getModels() != null && !p.getModels().isBlank()) {
            try {
                JsonNode node = om.readTree(p.getModels());
                JsonNode arr = node != null && node.isObject() ? node.path("text") : node;
                if (arr.isArray() && !arr.isEmpty()) return arr.get(0).asText();
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private boolean hasConfiguredTextModel(AiProvider provider) {
        return hasUsableCredential(provider) && pickModel(provider, null) != null;
    }

    private boolean hasUsableCredential(AiProvider provider) {
        return provider != null && provider.getApiKey() != null && !provider.getApiKey().isBlank();
    }

    private void writeLog(Cand c, UseCase useCase, AiClient.ChatResult r) {
        try {
            AiLog l = new AiLog();
            l.setProviderId(c.provider.getId());
            l.setUseCase(useCase.name());
            l.setModel(c.model);
            l.setOk(r.isOk());
            l.setLatencyMs((int) r.getLatencyMs());
            l.setPromptTokens(r.getPromptTokens());
            l.setCompletionTokens(r.getCompletionTokens());
            if (r.isOk() && r.getText() != null) {
                l.setPreview(r.getText().length() > 500 ? r.getText().substring(0, 500) : r.getText());
            } else {
                l.setError(r.getError());
            }
            logRepo.save(l);
        } catch (Exception e) {
            log.debug("ai log save failed: {}", e.toString());
        }
    }
}
