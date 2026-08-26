package com.douyin.mixcut.web;

import com.douyin.mixcut.domain.MaterialAnalysis;
import com.douyin.mixcut.service.MaterialAnalysisService;
import com.douyin.mixcut.service.MaterialDeleteService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 素材结构化分析与受控永久删除。
 * 分析接口幂等：已运行中重复调用返回当前记录；删除接口先预览再确认。
 */
@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialAnalysisController {

    private final MaterialAnalysisService analysisService;
    private final MaterialDeleteService deleteService;

    /** 启动异步分析（ffmpeg 场景检测 + OCR/转写复用 + 可选 AI 标签）。 */
    @PostMapping("/{id}/analyze")
    public R<MaterialAnalysis> analyze(@PathVariable Long id) {
        try {
            return R.ok(analysisService.analyze(id));
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @Data
    public static class BatchIndexReq {
        private List<Long> materialIds;
        private Boolean force = false;
        private Integer limit = 24;
    }

    /** 批量索引当前素材库范围；只处理应用已有素材，不扫描任意本机目录。 */
    @PostMapping("/index")
    public R<Map<String, Object>> index(@RequestBody(required = false) BatchIndexReq req) {
        boolean force = req != null && Boolean.TRUE.equals(req.getForce());
        int limit = req == null || req.getLimit() == null ? 24 : req.getLimit();
        return R.ok(analysisService.queueIndex(req == null ? null : req.getMaterialIds(), force, limit));
    }

    /** 读取最新分析结果、镜头片段与转写缓存。 */
    @GetMapping("/{id}/analysis")
    public R<Map<String, Object>> analysis(@PathVariable Long id) {
        return R.ok(analysisService.read(id));
    }

    /** 删除影响预览：列出将被删除的应用管理文件与记录，并标记是否被进行中任务阻止。 */
    @PostMapping("/{id}/delete-impact")
    public R<MaterialDeleteService.DeleteImpact> deleteImpact(@PathVariable Long id) {
        return R.ok(deleteService.preview(id));
    }

    @Data
    public static class PermanentDeleteReq { private Boolean confirm; }

    /** 确认永久删除：只删除应用管理文件与派生记录，外部扫描路径与成片不受影响。 */
    @PostMapping("/{id}/delete")
    public R<MaterialDeleteService.DeleteResult> delete(@PathVariable Long id,
                                                          @RequestBody(required = false) PermanentDeleteReq req) {
        if (req == null || !Boolean.TRUE.equals(req.getConfirm())) return R.fail("请完成第二次确认后再永久删除素材");
        try {
            return R.ok(deleteService.confirm(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }
}
