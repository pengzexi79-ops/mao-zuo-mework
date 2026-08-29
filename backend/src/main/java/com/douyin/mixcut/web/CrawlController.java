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

    @GetMapping("/image/search")
    public R<List<CrawlerGateway.RemoteItem>> searchImage(
            @RequestParam(defaultValue = "all") String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) Long projectId) {
        return R.ok(crawler.searchImage(source, keyword == null ? "" : keyword, Math.min(40, Math.max(1, limit)),
                projectId == null ? null : projectRepo.findById(projectId).orElse(null)));
    }

    @Data
    public static class ImportImageReq {
        private List<CrawlerGateway.RemoteItem> items;
        private String role = "body";
        private Long folderId;
    }

    @PostMapping("/image/import")
    public R<CrawlJob> importImage(@RequestBody ImportImageReq req) {
        if (req.getItems() == null || req.getItems().isEmpty()) return R.fail("未选择可导入的公开图片素材");
        if (req.getItems().size() > 200) return R.fail("单次最多导入 200 条素材");
        return R.ok(crawlJobService.submitImageItems(req.getItems(), req.getRole(), req.getFolderId()));
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
                    Map.entry("name", term + " · 公开图片检索"),
                    Map.entry("source", "openverse"), Map.entry("status", "search_ready"),
                    Map.entry("action", "search"), Map.entry("note", "应用内检索 Openverse 公开许可图片，可预览、勾选并批量导入素材库")));
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
        boolean freesound = !props.getFreesoundApiKey().isBlank();
        boolean pixabay = !props.getPixabayApiKey().isBlank();
        boolean pexels = !props.getPexelsApiKey().isBlank();
        List<Object> out = new ArrayList<>();
        out.add(source("wikimedia", "Wikimedia Commons 音视频图片", false, "direct", "ready", "公开许可音频 / 视频 / 图片", true, true,
                "https://commons.wikimedia.org/", null, "应用内可检索并显示每条许可证；无人值守自动补齐仅使用 CC0、公有领域或 CC BY 条目"));
        out.add(source("archive", "Internet Archive", false, "direct", "ready", "CC / 公有领域音频 / 视频", true, true,
                "https://archive.org/", null, "应用内可检索并显示许可证；无人值守自动补齐仅使用带可核验白名单许可的条目"));
        out.add(source("openverse", "Openverse 音频 / 图片", false, "direct", "ready", "公开许可音频 / 图片", true, false,
                "https://api.openverse.org/v1/", null, "已接入官方匿名 API 音频和图片检索；注册 OAuth 仅用于提高配额，应用不代登录"));
        out.add(source("mixkit", "Mixkit 免费音乐", false, "manual-search", "ready", "BGM / 音乐", true, false,
                "https://mixkit.co/free-stock-music/", null, "已接入公开页面检索，需人工确认条目许可后导入；不会用于无人值守自动补齐"));
        out.add(source("tosound", "淘声网 toSound", false, "manual-search", "ready", "公开音效 / BGM", true, false,
                "https://www.tosound.com/", null, "已接入公开页面检索，需人工确认条目许可后导入；不会代登录"));
        out.add(source("freesound", "Freesound 音效库", true, "key", freesound ? "ready" : "missing_key", "音效 / 环境声", true, false,
                "https://freesound.org/docs/api/", "freesound", "需在能力中心配置官方 API Key；未配置时不会请求来源"));
        out.add(source("pixabay", "Pixabay 视频 / 图片", true, "key", pixabay ? "ready" : "missing_key", "关键词视频 / 图片", true, true,
                "https://pixabay.com/api/docs/", "pixabay", "同一个官方 Key 可检索视频和图片；配置后可参与检索，视频才进入自动补齐"));
        out.add(source("pexels", "Pexels 视频 / 图片", true, "key", pexels ? "ready" : "missing_key", "竖版视频 / 图片", true, true,
                "https://www.pexels.com/api/", "pexels", "同一个官方 Key 可检索视频和图片；配置后可参与检索，视频才进入自动补齐"));
        boolean unsplash = !props.getUnsplashApiKey().isBlank();
        out.add(source("unsplash", "Unsplash 图片", true, "key", unsplash ? "ready" : "missing_key", "图片 / 商品背景", true, false,
                "https://unsplash.com/developers", "unsplash", "登录官方开发者中心创建 Access Key 后配置；应用只调用官方 API，不读取网页登录 Cookie"));

        out.add(source("ear0", "耳聆网 ear0", false, props.isAllowLoginCrawl() ? "login-enabled" : "login-disabled", "manual_only", "中文音效 / 环境声", false, false,
                "https://www.ear0.com/", null, "需要用户自行登录并确认授权；应用不会读取 Cookie、账号或密码"));
        out.add(source("mixkit-video", "Mixkit 免费视频", false, "official-page", "official_page_only", "免费视频素材", false, false,
                "https://mixkit.co/free-stock-video/", null, "当前无受控视频 API，仅提供官方页面供人工选择和导入"));
        out.add(source("coverr", "Coverr 免费视频", false, "official-page", "official_page_only", "免费视频素材", false, false,
                "https://coverr.co/", null, "当前未接入受控视频检索，仅提供官方页面"));
        out.add(source("videvo", "Videvo 视频素材", false, "official-page", "official_page_only", "视频 / 动效", false, false,
                "https://www.videvo.net/", null, "部分素材需要署名或登录；请在官方页面逐条确认授权后导入"));
        out.add(source("motionplaces", "Motion Places", false, "official-page", "official_page_only", "旅行 / 城市视频", false, false,
                "https://www.motionplaces.com/", null, "官方页面人工选择；不同素材授权条件不同"));
        out.add(source("pixabay-music", "Pixabay 音乐", false, "official-page", "official_page_only", "BGM / 音效", false, false,
                "https://pixabay.com/music/", null, "官方音乐页面；当前应用不抓取网页，需人工选择后导入"));
        out.add(source("bensound", "Bensound 免费音乐", false, "official-page", "official_page_only", "BGM / 背景音乐", false, false,
                "https://www.bensound.com/free-music-for-videos", null, "部分授权需要署名或购买许可，请逐条核验"));
        out.add(source("zapsplat", "ZapSplat 音效", false, "official-page", "official_page_only", "音效 / 环境声", false, false,
                "https://www.zapsplat.com/", null, "官方页面人工下载；免费账户与署名条件以官方条款为准"));
        out.add(source("ccmixter", "ccMixter 音乐库", false, "official-page", "official_page_only", "CC 授权音乐", false, false,
                "https://ccmixter.org/", null, "官方页面人工选择；混音条目的授权条件需要逐条确认"));
        out.add(source("aigei", "爱给网", false, "official-page", "official_page_only", "中文音频 / 视频 / 图片", false, false,
                "https://www.aigei.com/", null, "部分内容需要登录或会员；仅提供官方入口，不读取账号信息"));
        out.add(source("zcool", "站酷素材", false, "official-page", "official_page_only", "中文图片 / 视频 / 设计素材", false, false,
                "https://www.zcool.com.cn/", null, "需按作品页面确认授权，当前不接入网页自动抓取"));
        out.add(source("xinpianchang", "新片场素材", false, "official-page", "official_page_only", "中文视频素材", false, false,
                "https://www.xinpianchang.com/", null, "账号和授权条件由官方控制，人工下载后导入"));
        out.add(source("vjshi", "VJ师", false, "official-page", "official_page_only", "视频 / 动效素材", false, false,
                "https://www.vjshi.com/", null, "多数素材按授权或购买使用，当前只提供官方入口"));
        out.add(source("polyhaven", "Poly Haven 3D/HDR", false, "official-page", "official_page_only", "3D 模型 / HDR / 贴图（CC0）", false, false,
                "https://polyhaven.com/", null, "CC0 素材库；当前作为官方下载入口，不直接导入 3D 文件"));
        out.add(source("ambientcg", "AmbientCG 材质", false, "official-page", "official_page_only", "PBR 材质贴图（CC0）", false, false,
                "https://ambientcg.com/", null, "CC0 材质库；下载后可作为本地图片/贴图导入"));
        out.add(source("sketchfab", "Sketchfab", false, "official-page", "official_page_only", "3D 模型", false, false,
                "https://sketchfab.com/", null, "模型授权差异较大，需逐条确认后下载"));
        out.add(source("kenney", "Kenney 游戏素材", false, "official-page", "official_page_only", "CC0 游戏 / UI 素材", false, false,
                "https://kenney.nl/assets", null, "CC0 素材包，适合 UI、图标和道具参考"));
        out.add(source("dummyjson", "DummyJSON 电商数据", false, "official-page", "official_page_only", "电商产品 JSON 数据", false, false,
                "https://dummyjson.com/products", null, "公开数据 API，适合商品文案和选品参考，不是可直接出片的媒体源"));
        return R.ok(out);
    }

    private Map<String, Object> source(String key, String name, boolean needKey, String mode, String status,
                                       String usage, boolean searchReady, boolean autoFill, String officialUrl,
                                       String configId, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("name", name);
        row.put("needKey", needKey);
        row.put("mode", mode);
        row.put("status", status);
        row.put("usage", usage);
        row.put("searchReady", searchReady);
        row.put("autoFill", autoFill);
        row.put("mediaTypes", switch (key) {
            case "wikimedia" -> List.of("audio", "video", "image");
            case "archive" -> List.of("audio", "video");
            case "openverse" -> List.of("audio", "image");
            case "pixabay", "pexels" -> List.of("video", "image");
            case "unsplash" -> List.of("image");
            case "freesound", "mixkit", "tosound", "ear0", "pixabay-music", "bensound", "zapsplat", "ccmixter" -> List.of("audio");
            default -> List.of("video", "image", "audio");
        });
        row.put("autoFillTypes", autoFill ? List.of("video") : List.of());
        row.put("officialUrl", officialUrl);
        row.put("authUrl", officialUrl);
        if (configId != null) {
            row.put("configId", configId);
            row.put("configKey", "APP_" + configId.toUpperCase(java.util.Locale.ROOT) + ("freesound".equals(configId) ? "_API_KEY" : "_API_KEY"));
        }
        row.put("note", note);
        return row;
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
