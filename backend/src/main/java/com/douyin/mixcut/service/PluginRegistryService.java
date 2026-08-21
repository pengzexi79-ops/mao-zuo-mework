package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.AppPlugin;
import com.douyin.mixcut.repository.Repositories.PluginRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地插件注册表：只管理插件元数据、启用状态和 manifest，不执行插件代码。
 * 用户开发的新功能可以先在这里登记，再由前端按 entryUrl 或 manifest 进行接入。
 */
@Service
@RequiredArgsConstructor
public class PluginRegistryService {
    private final PluginRepo pluginRepo;
    private final ObjectMapper om = new ObjectMapper();

    @Data
    public static class PluginRequest {
        private String key;
        private String name;
        private String category;
        private String description;
        private String entryUrl;
        private Integer priority;
        private Boolean enabled;
        private String manifest;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppPlugin plugin : pluginRepo.findAllByOrderByPriorityAscIdAsc()) {
            out.add(view(plugin));
        }
        return out;
    }

    public Map<String, Object> create(PluginRequest request) {
        AppPlugin plugin = new AppPlugin();
        apply(plugin, request, true);
        return view(pluginRepo.save(plugin));
    }

    public Map<String, Object> update(Long id, PluginRequest request) {
        AppPlugin plugin = pluginRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("插件不存在"));
        apply(plugin, request, false);
        return view(pluginRepo.save(plugin));
    }

    public void delete(Long id) {
        if (!pluginRepo.existsById(id)) {
            throw new IllegalArgumentException("插件不存在");
        }
        pluginRepo.deleteById(id);
    }

    public Map<String, Object> view(AppPlugin plugin) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", plugin.getId());
        value.put("key", plugin.getKey());
        value.put("name", plugin.getName());
        value.put("category", plugin.getCategory());
        value.put("description", plugin.getDescription());
        value.put("entryUrl", plugin.getEntryUrl());
        value.put("priority", plugin.getPriority());
        value.put("enabled", Boolean.TRUE.equals(plugin.getEnabled()));
        value.put("manifestText", plugin.getManifest() == null ? "{}" : plugin.getManifest());
        value.put("manifest", parseManifest(plugin.getManifest()));
        value.put("createdAt", plugin.getCreatedAt());
        value.put("updatedAt", plugin.getUpdatedAt());
        return value;
    }

    private void apply(AppPlugin plugin, PluginRequest request, boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("插件信息不能为空");
        }
        String key = cleanKey(request.getKey());
        if (creating || request.getKey() != null) {
            if (key.isBlank()) throw new IllegalArgumentException("插件标识不能为空");
            plugin.setKey(key);
        }
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isBlank()) throw new IllegalArgumentException("插件名称不能为空");
            plugin.setName(name);
        } else if (creating && (plugin.getName() == null || plugin.getName().isBlank())) {
            throw new IllegalArgumentException("插件名称不能为空");
        }
        if (request.getCategory() != null) plugin.setCategory(request.getCategory().trim());
        if (request.getDescription() != null) plugin.setDescription(request.getDescription().trim());
        if (request.getEntryUrl() != null) plugin.setEntryUrl(normalizeUrl(request.getEntryUrl()));
        if (request.getPriority() != null) plugin.setPriority(Math.max(1, Math.min(999, request.getPriority())));
        if (request.getEnabled() != null) plugin.setEnabled(request.getEnabled());
        if (request.getManifest() != null) plugin.setManifest(normalizeManifest(request.getManifest()));
        if (creating && plugin.getManifest() == null) plugin.setManifest("{}");
    }

    private String cleanKey(String raw) {
        if (raw == null) return "";
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._:-]+", "-");
        key = key.replaceAll("^-+|-+$", "");
        if (key.length() > 128) key = key.substring(0, 128);
        return key;
    }

    private String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return "";
        try {
            java.net.URI uri = java.net.URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("插件入口必须使用 http 或 https");
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("插件入口 URL 格式错误");
        }
    }

    private String normalizeManifest(String raw) {
        String value = raw == null || raw.isBlank() ? "{}" : raw.trim();
        try {
            JsonNode node = om.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("插件 manifest 必须是 JSON 对象");
            }
            return om.writeValueAsString(node);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("插件 manifest 格式无效");
        }
    }

    private JsonNode parseManifest(String raw) {
        try {
            JsonNode node = om.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            return node != null && node.isObject() ? node : om.createObjectNode();
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }
}
