package com.douyin.mixcut.web;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.CrawlJob;
import com.douyin.mixcut.domain.CrawlTask;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.service.MaterialService;
import com.douyin.mixcut.service.CrawlJobService;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.repository.MaterialStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页素材抓取。
 *
 * 合规提示写在接口返回里，不藏着：需要登录态的站点默认不抓，
 * 抓下来的素材版权归属由使用方负责。交付时这段要进合同附件。
 */
@RestController
@RequestMapping("/api/crawl")
@RequiredArgsConstructor
public class CrawlController {

    private final CrawlerGateway crawler;
    private final AppProps props;
    private final MaterialService materialService;
    private final CrawlJobService crawlJobService;
    private final ProjectRepo projectRepo;
    private final MaterialStore materialRepo;

    @Data
    public static class VideoReq {
        private String url;
        private String role = "body";
    }

    @PostMapping("/video")
    public R<Material> video(@RequestBody VideoReq req) {
        if (req.getUrl() == null || req.getUrl().isBlank()) return R.fail("请填写地址");
        CrawlerGateway.FetchResult r = crawler.fetchVideo(req.getUrl().trim());
        if (!r.isOk()) return R.fail(r.getMessage());
        return R.ok(materialService.registerDownloaded(r.getFilePath(), safeRecordUrl(req.getUrl()), parseRole(req.getRole())));
    }

    /** 直链下载图片/音频（可扩展视频直链）并登记入库，按类型与角色分类。 */
    @PostMapping("/direct")
    public R<com.douyin.mixcut.domain.Material> direct(@RequestBody DirectReq req) {
        if (req.getUrl() == null || req.getUrl().isBlank()) return R.fail("请填写下载地址");
        try {
            Path downloaded = crawler.downloadDirect(req.getUrl().trim(), req.getType() == null ? "" : req.getType());
            MaterialRole role = req.getRole() == null ? MaterialRole.none : parseRole(req.getRole());
            return R.ok(materialService.registerDownloaded(downloaded.toString(), safeRecordUrl(req.getUrl()), role));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            return R.fail("下载失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    @Data
    public static class DirectReq {
        private String url;
        private String type;
        private String role;
    }

    @Data
    public static class ImagesReq {
        private String url;
        private String role = "body";
    }

    /** 图集/图片页面批量抓取：gallery-dl 下载后逐张登记入库。 */
    @PostMapping("/images")
    public R<List<Material>> images(@RequestBody ImagesReq req) {
        if (req.getUrl() == null || req.getUrl().isBlank()) return R.fail("请填写图集或图片页面地址");
        CrawlerGateway.FetchResult r = crawler.fetchImages(req.getUrl().trim());
        if (!r.isOk()) return R.fail(r.getMessage());
        List<Material> registered = new ArrayList<>();
        MaterialRole role = parseRole(req.getRole());
        String sourceUrl = safeRecordUrl(req.getUrl());
        for (String p : r.getImagePaths() == null ? List.<String>of() : r.getImagePaths()) {
            try {
                registered.add(materialService.registerDownloaded(p, sourceUrl, role));
            } catch (Exception ignored) {
                // 单张入库失败不阻塞其余图片
            }
        }
        if (registered.isEmpty()) return R.fail("已下载但入库失败，请检查素材目录权限");
        return R.ok(registered);
    }
    @Data
    public static class BatchVideoReq {
        private List<String> urls;
        private String role = "body";
        private Long folderId;
    }

    @PostMapping("/video/batch")
    public R<CrawlJob> batch(@RequestBody BatchVideoReq req) {
        if (req.getUrls() == null || req.getUrls().isEmpty()) return R.fail("请至少填写一个公开链接");
        if (req.getUrls().size() > 200) return R.fail("单次最多抓取 200 条链接");
        return R.ok(crawlJobService.submitVideos(req.getUrls(), req.getRole(), req.getFolderId()));
    }

    @GetMapping("/jobs")
    public R<List<CrawlJob>> jobs() { return R.ok(crawlJobService.recent()); }

    @GetMapping("/jobs/{id}")
    public R<Map<String, Object>> job(@PathVariable Long id) {
        CrawlJob job = crawlJobService.detail(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", job);
        List<com.douyin.mixcut.domain.CrawlTask> tasks = crawlJobService.tasks(id);
        tasks.forEach(task -> {
            task.setGuardRejected(isGuardRejected(task));
            task.setDownloadStatus(task.getStatus());
            if (task.getMaterialId() == null) {
                task.setAdmissionStatus(com.douyin.mixcut.domain.JobStatus.done.name().equals(task.getStatus()) ? "not_applicable" : "pending");
                task.setAdmissionReason(task.getMessage());
                task.setFileExists(false);
                task.setUsable(false);
            } else {
                Material material = materialRepo.findById(task.getMaterialId()).orElse(null);
                boolean exists = material != null && material.getFilePath() != null && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(material.getFilePath()));
                boolean usable = material != null && material.getStatus() == Material.Status.ready && exists;
                task.setAdmissionStatus(usable ? "ready" : "failed");
                task.setAdmissionReason(usable ? null : (task.getMessage() == null ? "素材未通过质量准入或文件不可读" : task.getMessage()));
                task.setFileExists(exists);
                task.setUsable(usable);
            }
        });
        result.put("tasks", tasks);
        long downloaded = tasks.stream().filter(task -> "done".equals(task.getDownloadStatus())).count();
        long admitted = tasks.stream().filter(CrawlTask::isUsable).count();
        long admissionFailed = tasks.stream().filter(task -> "failed".equals(task.getAdmissionStatus())).count();
        result.put("downloadedCount", downloaded);
        result.put("admittedCount", admitted);
        result.put("admissionFailedCount", admissionFailed);
        long elapsed = job.getCreatedAt() == null ? 0 : java.time.Duration.between(job.getCreatedAt(), java.time.LocalDateTime.now()).getSeconds();
        int current = job.getCurrentItem() == null ? 0 : job.getCurrentItem();
        int total = Math.max(1, job.getTotal() == null ? 1 : job.getTotal());
        result.put("elapsedSec", elapsed);
        result.put("itemsPerMinute", elapsed > 0 ? Math.round(current * 60.0 / elapsed * 100.0) / 100.0 : 0);
        result.put("etaSec", current > 0 && elapsed > 0 ? Math.max(0, Math.round((total - current) / (current / (double) elapsed))) : 0);
        return R.ok(result);
    }

    @PostMapping("/jobs/{id}/cancel")
    public R<Void> cancelJob(@PathVariable Long id) { crawlJobService.cancel(id); return R.ok(); }

    @PostMapping("/jobs/{id}/retry")
    public R<CrawlJob> retryJob(@PathVariable Long id) { return R.ok(crawlJobService.retryFailed(id)); }

    @DeleteMapping("/jobs/{id}")
    public R<Void> deleteJob(@PathVariable Long id) {
        try { crawlJobService.deleteJob(id); return R.ok(); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @PostMapping("/jobs/cleanup")
    public R<Integer> cleanupJobs() { return R.ok(crawlJobService.cleanupTerminal()); }

    @GetMapping("/audio/search")
    public R<List<CrawlerGateway.RemoteItem>> searchAudio(
            @RequestParam(defaultValue = "all") String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) Long projectId) {
        return R.ok(crawler.searchAudio(source, keyword == null ? "" : keyword, Math.min(40, Math.max(1, limit)),
                projectId == null ? null : projectRepo.findById(projectId).orElse(null)));
    }

    @Data
    public static class ImportAudioReq {
        private List<CrawlerGateway.RemoteItem> items;
        private String role = "bgm";
        private Long folderId;
    }

    @PostMapping("/audio/import")
    public R<CrawlJob> importAudio(@RequestBody ImportAudioReq req) {
        if (req.getItems() == null || req.getItems().isEmpty()) return R.fail("未选择可导入的公开音频素材");
        if (req.getItems().size() > 200) return R.fail("单次最多导入 200 条素材");
        return R.ok(crawlJobService.submitAudio(req.getItems(), req.getRole(), req.getFolderId()));
    }

    @GetMapping("/video/search")
    public R<List<CrawlerGateway.RemoteItem>> searchVideo(
            @RequestParam(defaultValue = "all") String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) Long projectId) {
        return R.ok(crawler.searchVideo(source, keyword == null ? "" : keyword, Math.min(40, Math.max(1, limit)),
                projectId == null ? null : projectRepo.findById(projectId).orElse(null)));
    }

    @Data
    public static class ImportVideoReq {
        private List<CrawlerGateway.RemoteItem> items;
        private String role = "body";
        private Long folderId;
    }

    @PostMapping("/video/import")
    public R<CrawlJob> importVideo(@RequestBody ImportVideoReq req) {
        if (req.getItems() == null || req.getItems().isEmpty()) return R.fail("未选择可导入的公开视频素材");
        if (req.getItems().size() > 200) return R.fail("单次最多导入 200 条素材");
        return R.ok(crawlJobService.submitVideoItems(req.getItems(), req.getRole(), req.getFolderId()));
    }

    @GetMapping("/curated")
    public R<List<Map<String, Object>>> curated() {
        List<String> terms = List.of(
                "food", "cooking", "skincare", "beauty", "technology", "unboxing", "travel", "nature",
                "family", "baby", "fitness", "wellness", "product", "coffee", "office", "lifestyle",
                "fashion", "home", "pet", "city", "ocean", "mountain", "forest", "sunset", "sunrise",
                "street", "architecture", "education", "science", "business", "music", "concert",
                "people", "portrait", "hands", "craft", "garden", "flower", "water", "rain", "snow",
                "summer", "winter", "holiday", "festival", "sports", "car", "traveling", "shopping", "cleaning");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i);
            out.add(Map.ofEntries(
                    Map.entry("id", "video-" + (i + 1)), Map.entry("category", "video"),
                    Map.entry("name", term + " · 受控公开视频检索"), Map.entry("keyword", term),
                    Map.entry("source", "wikimedia"), Map.entry("status", "search_ready"),
                    Map.entry("action", "search"), Map.entry("note", "仅通过已接入的许可筛选检索；结果仍需链接、媒体类型和质量准入")));
            out.add(Map.ofEntries(
                    Map.entry("id", "audio-" + (i + 1)), Map.entry("category", "audio"),
                    Map.entry("name", term + " · 受控音频检索"), Map.entry("keyword", term),
                    Map.entry("source", "wikimedia"), Map.entry("status", "search_ready"),
                    Map.entry("action", "search"), Map.entry("note", "仅通过已接入的许可筛选检索；结果仍需链接、媒体类型和质量准入")));
            out.add(Map.ofEntries(
                    Map.entry("id", "image-" + (i + 1)), Map.entry("category", "image"),
                    Map.entry("name", term + " · 官方图片检索"),
                    Map.entry("url", "https://commons.wikimedia.org/w/index.php?search=" +
                            java.net.URLEncoder.encode(term, java.nio.charset.StandardCharsets.UTF_8) +
                            "&title=Special:MediaSearch&type=image"),
                    Map.entry("source", "wikimedia"), Map.entry("status", "official_page_only"),
                    Map.entry("action", "official"), Map.entry("note", "只打开官方检索页；逐条确认许可后再下载到本地")));
            out.add(Map.ofEntries(
                    Map.entry("id", "extra-" + (i + 1)), Map.entry("category", "extra"),
                    Map.entry("name", term + " · 官方参考入口"),
                    Map.entry("url", "https://commons.wikimedia.org/w/index.php?search=" +
                            java.net.URLEncoder.encode(term, java.nio.charset.StandardCharsets.UTF_8) +
                            "&title=Special:MediaSearch"),
                    Map.entry("source", "wikimedia"), Map.entry("status", "official_page_only"),
                    Map.entry("action", "official"), Map.entry("note", "仅官方参考入口；当前不直接抓取 3D、商品网页或模型页面")));
        }
        return R.ok(out);
    }

    @GetMapping("/sources")
    public R<List<Object>> sources() {
        return R.ok(List.of(
                java.util.Map.of("key", "mixkit", "name", "Mixkit 免费音乐", "needKey", false,
                        "mode", "direct", "status", "ready", "usage", "BGM / 音乐", "note", "已接入公开音频检索；导入前仍需确认具体条目许可"),
                java.util.Map.of("key", "freesound", "name", "Freesound 音效库", "needKey", true,
                        "mode", "key", "status", props.getFreesoundApiKey().isBlank() ? "missing_key" : "ready", "usage", "音效 / 环境声", "configKey", "APP_FREESOUND_API_KEY", "authUrl", "https://freesound.org/apiv2/apply/", "note", "官方申请 API Key 后，在本机环境变量配置 APP_FREESOUND_API_KEY"),
                java.util.Map.of("key", "pixabay", "name", "Pixabay 视频素材", "needKey", true,
                        "mode", "key", "status", props.getPixabayApiKey().isBlank() ? "missing_key" : "ready", "usage", "关键词公开视频", "configKey", "APP_PIXABAY_API_KEY", "authUrl", "https://pixabay.com/api/docs/", "note", "已接入官方视频检索；配置自己的 API Key 后可按关键词搜索并进入受控导入队列"),
                java.util.Map.of("key", "pexels", "name", "Pexels 视频素材", "needKey", true,
                        "mode", "key", "status", props.getPexelsApiKey().isBlank() ? "missing_key" : "ready", "usage", "竖版关键词公开视频", "configKey", "APP_PEXELS_API_KEY", "authUrl", "https://www.pexels.com/api/", "note", "已接入官方视频检索（仅走 api.pexels.com，不抓网页）；配置自己的 API Key 后可按关键词搜索竖版视频并进入受控导入队列"),
                java.util.Map.of("key", "wikimedia", "name", "Wikimedia Commons", "needKey", false,
                        "mode", "direct", "status", "ready", "usage", "公开许可音频 / 视频", "note", "已接入许可筛选；音频和公开视频均可按关键词检索，导入前仍需确认具体条目许可证"),
                java.util.Map.ofEntries(
                        java.util.Map.entry("key", "archive"),
                        java.util.Map.entry("name", "Internet Archive"),
                        java.util.Map.entry("needKey", false),
                        java.util.Map.entry("mode", "direct"),
                        java.util.Map.entry("status", "ready"),
                        java.util.Map.entry("usage", "CC / 公有领域音频 / 视频"),
                        java.util.Map.entry("note", "已接入许可筛选，仅展示带 CC 或公有领域声明的条目")),
                java.util.Map.of("key", "openverse", "name", "Openverse", "needKey", true,
                        "mode", "oauth", "status", "unsupported", "usage", "公开许可图像 / 音频", "authUrl", "https://api.openverse.org/register/", "note", "仅提供官方注册入口；当前版本未接入 OAuth 回调和检索，不显示为可用来源"),
                java.util.Map.of("key", "ear0", "name", "耳聆网 ear0", "needKey", false,
                        "mode", props.isAllowLoginCrawl() ? "login-enabled" : "login-disabled", "authUrl", "https://www.ear0.com/", "note", "需登录，默认关闭；开启前必须确认你拥有下载和使用授权"),
                java.util.Map.of("key", "mixkit-video", "name", "Mixkit 免费视频", "needKey", false,
                        "mode", "official-page", "status", "official_page_only", "usage", "公开免费商用视频素材", "authUrl", "https://mixkit.co/free-stock-video/", "note", "当前未接入受控视频检索，仅提供官方页"),
                java.util.Map.of("key", "coverr", "name", "Coverr 免费视频", "needKey", false,
                        "mode", "official-page", "status", "official_page_only", "usage", "公开免费商用视频素材", "authUrl", "https://coverr.co/", "note", "当前未接入受控视频检索，仅提供官方页"),
                java.util.Map.of("key", "bensound", "name", "Bensound 免费音乐", "needKey", false,
                        "mode", "official-page", "status", "official_page_only", "usage", "BGM / 背景音乐", "authUrl", "https://www.bensound.com/free-music-for-videos", "note", "当前未接入受控音频检索，仅提供官方页"),
                java.util.Map.of("key", "ccmixter", "name", "ccMixter 音乐库", "needKey", false,
                        "mode", "official-page", "status", "official_page_only", "usage", "CC 授权音乐 / 人声", "authUrl", "https://ccmixter.org/", "note", "当前未接入受控音频检索，仅提供官方页"),                java.util.Map.of("key", "polyhaven", "name", "Poly Haven 3D/HDR", "needKey", false,
                        "mode", "official-page", "status", "official_page_only", "usage", "3D 模型 / HDR / 贴图（CC0）", "authUrl", "https://polyhaven.com/", "note", "CC0 免授权；适合 AI 生图/3D 场景参考，可直接浏览下载"),
                java.util.Map.of("key", "ambientcg", "name", "AmbientCG 材质", "needKey", false,
                        "mode", "official-page", "status", "official_page_only", "usage", "PBR 材质贴图（CC0）", "authUrl", "https://ambientcg.com/", "note", "CC0 免授权材质库；适合商品/场景贴图"),
                java.util.Map.of("key", "kenney", "name", "Kenney 游戏素材", "needKey", false,
                        "mode", "official-page", "status", "official_page_only", "usage", "CC0 游戏/UI 素材包", "authUrl", "https://kenney.nl/assets", "note", "CC0 素材包，适合界面/图标/道具素材"),
                java.util.Map.of("key", "dummyjson", "name", "DummyJSON 电商数据", "needKey", false,
                        "mode", "official-page", "status", "official_page_only", "usage", "电商产品 JSON 数据（商品/图片/描述）", "authUrl", "https://dummyjson.com/products", "note", "免费电商产品数据 API，AI 可据此生成商品文案/选品参考"),                java.util.Map.of("key", "tosound", "name", "淘声网 toSound", "needKey", false,
                        "mode", "direct", "status", "ready", "authUrl", "https://www.tosound.com/", "usage", "公开音效 / BGM", "note", "已预置公开检索；打开官网试听并确认许可后可导入，不需要应用代登录")
        ));
    }

    private boolean isGuardRejected(com.douyin.mixcut.domain.CrawlTask task) {
        if (task == null || !"failed".equals(task.getStatus())) return false;
        if ("URL_GUARD_REJECTED".equals(task.getErrorCode())) return true;
        // Rows created before structured crawl diagnostics remain readable after upgrade.
        String message = task.getMessage() == null ? "" : task.getMessage();
        return message.startsWith("URL 格式错误或目标地址不允许访问")
                || message.startsWith("下载地址格式错误或目标地址不允许访问");
    }

    /** Preserve a useful source reference without persisting query parameters that may contain credentials. */
    private String safeRecordUrl(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) return "[invalid URL]";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception e) {
            return "[invalid URL]";
        }
    }

    private MaterialRole parseRole(String s) {
        try {
            return MaterialRole.valueOf(s);
        } catch (Exception e) {
            return MaterialRole.body;
        }
    }
}
