package com.douyin.mixcut.web;

import com.douyin.mixcut.repository.Repositories.*;
import com.douyin.mixcut.service.AiService;
import com.douyin.mixcut.service.BootstrapService;
import com.douyin.mixcut.service.MaterialService;
import com.douyin.mixcut.service.ReleaseNotesService;
import com.douyin.mixcut.service.LocalReleaseHistoryService;
import com.douyin.mixcut.service.ConnectivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** System checks and dashboard overview. */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final BootstrapService bootstrap;
    private final MaterialService materialService;
    private final AiService aiService;
    private final ReleaseNotesService releaseNotesService;
    private final LocalReleaseHistoryService localReleaseHistoryService;
    private final JobRepo jobRepo;
    private final JobOutputRepo outputRepo;
    private final WorkflowRepo workflowRepo;
    private final ProjectRepo projectRepo;
    @Qualifier("renderExecutor") private final ThreadPoolTaskExecutor renderExecutor;
    private final ConnectivityService connectivityService;

    @GetMapping(value = "/env", produces = "text/html")
    public org.springframework.http.ResponseEntity<Void> envPage() {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                .location(java.net.URI.create("/#/capabilities?view=environment")).build();
    }

    @GetMapping(value = "/env", produces = "application/json")
    public R<Map<String, Object>> env(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean refresh) {
        return R.ok(bootstrap.env(refresh));
    }

    @GetMapping("/connectivity")
    public R<List<Map<String, Object>>> connectivity() {
        return R.ok(connectivityService.checkAll());
    }

    @GetMapping("/connectivity/{target}")
    public R<Map<String, Object>> connectivityTarget(@org.springframework.web.bind.annotation.PathVariable String target) {
        return R.ok(connectivityService.check(target));
    }

    @GetMapping("/release-notes")
    public R<Map<String, Object>> releaseNotes(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int historyLimit) {
        // Keep the complete local history visible even when an older frontend omits the parameter.
        return R.ok(localReleaseHistoryService.view(historyLimit));
    }

    @PostMapping("/capabilities/install")
    public R<Map<String, Object>> installCapability(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        return R.ok(bootstrap.installCapability(body == null ? null : body.get("key")));
    }
    @GetMapping("/capabilities")
    public R<java.util.List<Map<String, Object>>> capabilities(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean refresh) {
        return R.ok(bootstrap.capabilities(refresh));
    }
    /** Curated, non-proxy resource links. The server never fetches these URLs. */
    @GetMapping("/resources")
    public R<List<Map<String, Object>>> resources() {
        return R.ok(List.of(
                resource("open-courseware", "开放课程入口", "开放学习", "通过官方入口查找公开课程。", "https://ocw.mit.edu/", false, false),
                resource("open-books", "开放图书入口", "开放资料", "通过 Open Library 查找可合法阅读的公开资料。", "https://openlibrary.org/", false, false),
                resource("open-academic", "开放学术检索", "学术", "通过 Semantic Scholar 官方入口检索论文。", "https://www.semanticscholar.org/", false, false),
                resource("open-media", "开放媒体素材", "素材", "Wikimedia Commons、Openverse 和 Internet Archive 官方入口。", "https://commons.wikimedia.org/", false, false),
                resource("ffmpeg", "FFmpeg 官方文档", "开发环境", "本地视频、音频和图片处理基础工具。", "https://ffmpeg.org/documentation.html", false, false),
                resource("rembg", "rembg 开源项目", "图像处理", "本地背景移除能力的官方项目入口。", "https://github.com/danielgatis/rembg", false, false),
                resource("demucs", "Demucs 开源项目", "音频处理", "本地人声和伴奏分离能力的官方项目入口。", "https://github.com/facebookresearch/demucs", false, false),
                resource("ai-provider", "AI 服务商配置", "AI 模型", "在 AI 接入页配置自己的服务商、模型和密钥。", "#/ai", true, false),
                resource("plugin-center", "插件接口", "扩展", "登记自定义插件入口、manifest 和启用状态。", "#/capabilities", false, false),
                resource("pixabay-api", "Pixabay API", "视频素材", "需要用户自行申请 API Key，应用只调用官方接口。", "https://pixabay.com/api/docs/", true, false),
                resource("pexels-api", "Pexels API", "视频素材", "需要用户自行申请 API Key，应用只调用官方接口。", "https://www.pexels.com/api/", true, false),
                resource("hunyuan", "腾讯混元官方入口", "AI 模型", "账户与充值请以官方控制台和计费页面为准。", "https://cloud.tencent.com/product/hunyuan", true, true),
                resource("openai", "OpenAI 官方入口", "AI 模型", "账户与充值请以官方控制台和计费页面为准。", "https://platform.openai.com/", true, true)
        ));
    }

    private Map<String, Object> resource(String id, String name, String category, String description,
                                         String url, boolean accountRequired, boolean billingAvailable) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id); item.put("name", name); item.put("category", category);
        item.put("description", description); item.put("url", url);
        item.put("tags", List.of(accountRequired ? "需账号" : "公开入口", billingAvailable ? "官方计费" : "本地能力"));
        item.put("accountRequired", accountRequired); item.put("billingAvailable", billingAvailable);
        return item;
    }

    @GetMapping("/queue")
    public R<Map<String, Object>> queue() {
        Map<String, Object> result = new LinkedHashMap<>();
        var executor = renderExecutor.getThreadPoolExecutor();
        result.put("active", executor.getActiveCount());
        result.put("poolSize", executor.getPoolSize());
        result.put("queueSize", executor.getQueue().size());
        result.put("maxConcurrency", executor.getMaximumPoolSize());
        if (Boolean.TRUE.equals(bootstrap.env().get("databaseConnected"))) {
            result.put("pendingJobs", jobRepo.countByStatus("pending"));
            result.put("runningJobs", jobRepo.countByStatus("running"));
        } else {
            result.put("pendingJobs", 0);
            result.put("runningJobs", 0);
            result.put("degraded", true);
        }
        return R.ok(result);
    }

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> env = bootstrap.env();
        result.put("env", env);
        if (Boolean.FALSE.equals(env.get("databaseConnected"))) {
            result.put("materials", Map.of("total", 0L, "video", 0L, "audio", 0L, "image", 0L, "byRole", Map.of()));
            result.put("aiReady", false);
            result.put("jobs", 0L);
            result.put("outputs", 0L);
            result.put("workflows", 0L);
            result.put("projects", 0L);
            result.put("todo", List.of("数据库未连接：点击环境中心中的“配置 MySQL 认证”，测试并保存到本机 .env 后应用配置并重启后端。"));
            result.put("readyToRender", false);
            result.put("degraded", true);
            return R.ok(result);
        }
        var stats = materialService.stats();
        result.put("materials", stats);
        result.put("aiReady", aiService.ready());
        result.put("jobs", jobRepo.count());
        result.put("outputs", outputRepo.count());
        result.put("workflows", workflowRepo.count());
        result.put("projects", projectRepo.count());


        @SuppressWarnings("unchecked")
        Map<String, Long> byRole = (Map<String, Long>) stats.get("byRole");
        List<String> todo = new java.util.ArrayList<>();
        if (Boolean.FALSE.equals(bootstrap.env().get("ffmpeg"))) todo.add("安装 ffmpeg 并加入 PATH（否则无法出片）");
        // AI 只是文案增强：CopyService 会提供本地兜底钩子，不能把没有 API Key 的用户挡在出片流程外。
        long video = ((Number) stats.get("video")).longValue();
        if (video == 0) todo.add("到「素材库」扫描本机目录或上传视频素材");
        if (byRole != null) {
            if (byRole.getOrDefault("product", 0L) == 0) todo.add("标记若干条「产品」角色素材，成片才会插入自家产品段");
            if (byRole.getOrDefault("bgm", 0L) == 0) todo.add("导入至少一条 BGM，成片才有声音");
        }
        result.put("todo", todo);
        result.put("readyToRender", todo.isEmpty());
        return R.ok(result);
    }
}
