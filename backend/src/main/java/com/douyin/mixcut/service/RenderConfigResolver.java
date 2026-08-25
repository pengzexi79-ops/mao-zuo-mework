package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.dto.MixParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RenderConfigResolver {
    private final ObjectMapper om;

    public MixParams mergeProjectDefaults(String submittedJson, Project project) {
        MixParams base = new MixParams();
        try {
            var root = om.readTree(submittedJson == null ? "{}" : submittedJson);
            // Only the explicit frozen-payload marker may bypass project-default merging.
            if (root.path("snapshotVersion").isInt() && root.path("snapshotVersion").asInt() > 0
                    && root.path("admissionSnapshot").isObject()
                    && root.path("effectiveParams").isObject()) {
                return om.treeToValue(root.path("effectiveParams"), MixParams.class).normalized();
            }
        } catch (Exception ignored) { }
        if (project != null && project.getDefaultParams() != null && !project.getDefaultParams().isBlank()) {
            try { base = om.readValue(project.getDefaultParams(), MixParams.class); }
            catch (Exception e) { log.debug("项目默认参数解析失败: {}", e.toString()); }
        }
        if (submittedJson != null && !submittedJson.isBlank() && !"{}".equals(submittedJson.trim())) {
            try { return ((MixParams) om.readerForUpdating(base).readValue(submittedJson)).normalized(); }
            catch (Exception e) { log.warn("出片参数解析失败，使用项目默认值: {}", e.toString()); }
        }
        return base.normalized();
    }
}
