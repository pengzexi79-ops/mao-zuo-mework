package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.security.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 素材抓取网关。
 *
 * 视频：yt-dlp（首选，站点覆盖最广） → you-get（兜底） → 直链 HTTP；
 *      官方 API 视频检索：Pixabay API / Pexels API（需用户自配 Key，Pexels 仅走
 *      api.pexels.com 官方接口，Key 经 Authorization 请求头发送，不做网页抓取）。
 * 音频：Freesound API / Pixabay API（有官方 API，免费额度，合规）
 *      Mixkit（免费商用，页面直链解析 —— 仅手动导入：无公开 API 且服务条款不支持无人值守抓取）
 *      ear0 / tosound（需登录，默认关闭，由 app.allow-login-crawl 控制）
 *
 * 免登录自动源（无人值守自动填充）仅限 Wikimedia Commons 与 Internet Archive，
 * 且只接受 CC0 / Public Domain / CC BY 白名单许可，并携带可核验的许可元数据；
 * 其余来源一律不进入自动填充。
 *
 * 合规立场：只做"用户自己有权获取的素材"的下载代理。需要登录态的站点默认关闭，
 * 打开后由使用者自行承担合规责任 —— 交付给甲方时这条必须写进说明书。
 */
@Slf4j
@Component
public class CrawlerGateway {

    private final AppProps props;
    private final ProcRunner runner;
    private final WikimediaSourceAdapter wikimediaAdapter;
    private final InternetArchiveSourceAdapter archiveAdapter;
    private final ObjectMapper om = new ObjectMapper();

    public CrawlerGateway(AppProps props, ProcRunner runner) {
        this(props, runner, new WikimediaSourceAdapter(), new InternetArchiveSourceAdapter());
    }

    @Autowired
    public CrawlerGateway(AppProps props, ProcRunner runner, WikimediaSourceAdapter wikimediaAdapter,
                          InternetArchiveSourceAdapter archiveAdapter) {
        this.props = props;
        this.runner = runner;
        this.wikimediaAdapter = wikimediaAdapter;
        this.archiveAdapter = archiveAdapter;
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    /** Auto-fill lookups must fail promptly when a public index is blocked by the local network. */
    private static final ThreadLocal<Boolean> QUICK_PUBLIC_SEARCH = ThreadLocal.withInitial(() -> false);

    @Data
    public static class RemoteItem {
        private String source;
        private String title;
        private String pageUrl;
        private String downloadUrl;
        private String previewUrl;
        private Double duration;
        private String license;
        /** 许可原文链接（如 CC 许可 deed 页 / archive.org licenseurl），供人工核验与入库元数据；无链接时为空串。 */
        private String licenseUrl;
        private String type = "audio";
        /** 风格/情绪/乐器标签，逗号分隔（mixkit 有，用于前端二次筛选） */
        private String tags;
        /** 该条是否为"提示信息"而非真实可下载素材（如缺 Key、被合规拦截） */
        private boolean notice;
        /** 仅用于前端提供官方处理入口，不参与下载。 */
        private String authUrl;
        private String configKey;
        /** 搜索时关联的项目，用于保留导入审核上下文。 */
        private Long projectId;
        /** 本地项目/关键词命中分数，0 表示未命中。 */
        private Integer relevanceScore;
        /** 实际命中的关键词，逗号分隔，供前端和任务详情追踪。 */
        private String hitKeywords;
    }

    /** 构造一条提示性条目：不可下载，但要让用户在界面上看见原因 */
    private static RemoteItem notice(String source, String msg) {
        RemoteItem it = new RemoteItem();
        it.setSource(source);
        it.setTitle(msg);
        it.setLicense("notice");
        it.setNotice(true);
        return it;
    }

    private static RemoteItem notice(String source, String msg, String authUrl, String configKey) {
        RemoteItem it = notice(source, msg);
        it.setAuthUrl(authUrl);
        it.setConfigKey(configKey);
        return it;
    }

    @Data
    public static class FetchResult {
        private boolean ok;
        private String filePath;
        private List<String> imagePaths;
        private String message;
        private String via;
        private String errorCode;
        private String source;
        private Integer httpStatus;

        static FetchResult fail(String message) {
            return fail(message, "DOWNLOAD_FAILED", null, null);
        }

        static FetchResult fail(String message, String errorCode, String source, Integer httpStatus) {
            FetchResult result = new FetchResult();
            result.ok = false;
            result.message = message;
            result.errorCode = errorCode;
            result.source = source;
            result.httpStatus = httpStatus;
            return result;
        }
    }

    private record DownloadResult(boolean ok, String message, String errorCode, Integer httpStatus) {
        static DownloadResult success() {
            return new DownloadResult(true, null, null, null);
        }

        static DownloadResult fail(String message, String errorCode, Integer httpStatus) {
            return new DownloadResult(false, message, errorCode, httpStatus);
        }
    }

    // ==================== 视频下载 ====================

    public boolean ytdlpAvailable() {
        return runner.available(ytdlpCmd(), "--version");
    }

    /**
     * yt-dlp ships inside the bundled venv. Run it as a module via the project-local Python
     * (python -m yt_dlp) instead of the generated yt-dlp.exe shim: pip console-script launchers
     * bake in the build machine's absolute Python path and therefore break when the venv is
     * copied to another PC. A configured APP_YTDLP_PATH overrides this resolution.
     */
    private List<String> ytdlpCmd() {
        return "yt-dlp".equals(props.getYtdlpPath())
                ? List.of(props.localPythonPath(), "-m", "yt_dlp")
                : List.of(props.getYtdlpPath());
    }

    /** you-get ships inside the bundled venv; see {@link #ytdlpCmd()} for why it runs as a module. */
    private List<String> yougetCmd() {
        return "you-get".equals(props.getYougetPath())
                ? List.of(props.localPythonPath(), "-m", "you_get")
                : List.of(props.getYougetPath());
    }

    public boolean yougetAvailable() {
        return runner.available(yougetCmd(), "--version");
    }

    /**
     * 从网页地址抓取视频。返回落地文件路径。
     */
    public FetchResult fetchVideo(String url) {
        final String validatedUrl;
        try {
            // Validate before passing a user URL to any external downloader or HTTP client.
            validatedUrl = UrlGuard.validate(url);
        } catch (IllegalArgumentException e) {
            return FetchResult.fail("URL 格式错误或目标地址不允许访问: " + safeUrlError(e),
                    "URL_GUARD_REJECTED", null, null);
        }

        Path dir = props.downloads();
        String stamp = String.valueOf(System.currentTimeMillis());
        long startedAt = System.currentTimeMillis();
        Set<Path> before = snapshotFiles(dir);

        String toolFailureDetail = null;

        // 1) yt-dlp
        if (ytdlpAvailable()) {
            Path tpl = dir.resolve(stamp + "_%(title).60s.%(ext)s");
            List<String> cmd = new ArrayList<>(ytdlpCmd());
            cmd.addAll(List.of(
                    "--no-playlist", "--no-warnings",
                    "--merge-output-format", "mp4",
                    "-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b",
                    "-o", tpl.toString(), validatedUrl));
            ProcRunner.Result r = runner.run(cmd, 1800);
            Path got = newestDownloadedMedia(dir, before, startedAt, stamp);
            if (r.ok() && got != null) {
                FetchResult fr = new FetchResult();
                fr.ok = true;
                fr.filePath = got.toString();
                fr.via = "yt-dlp";
                return fr;
            }
            log.warn("yt-dlp failed for {}: {}", safeUrl(validatedUrl), tailOf(r.out()));
            toolFailureDetail = describeToolFailure("yt-dlp", r);
        }

        // 2) you-get
        if (yougetAvailable()) {
            List<String> cmd = new ArrayList<>(yougetCmd());
            cmd.addAll(List.of(
                    "--no-caption", "-o", dir.toString(), "-O", stamp + "_yg", validatedUrl));
            ProcRunner.Result r = runner.run(cmd, 1800);
            Path got = newestDownloadedMedia(dir, before, startedAt, stamp);
            if (r.ok() && got != null) {
                FetchResult fr = new FetchResult();
                fr.ok = true;
                fr.filePath = got.toString();
                fr.via = "you-get";
                return fr;
            }
            log.warn("you-get failed for {}: {}", safeUrl(validatedUrl), tailOf(r.out()));
            toolFailureDetail = describeToolFailure("you-get", r);
        }

        // 3) 直链：先探测响应类型，不能只依赖 URL 后缀。
        if (looksLikeDirectMedia(validatedUrl) || likelyPublicMediaEndpoint(validatedUrl)) {
            Path dst = dir.resolve(stamp + "_" + safeName(fileNameOf(validatedUrl)));
            DownloadResult download = downloadTo(validatedUrl, dst);
            if (download.ok()) {
                FetchResult fr = new FetchResult();
                fr.ok = true;
                fr.filePath = dst.toString();
                fr.via = "http";
                return fr;
            }
            return FetchResult.fail(download.message(), download.errorCode(), "http", download.httpStatus());
        }

        String available = ytdlpAvailable() || yougetAvailable()
                ? (toolFailureDetail == null
                    ? "下载工具未能获取此页面。请确认链接公开、免登录、未受 DRM 或地区限制，并尝试来源提供的直接媒体地址。"
                    : toolFailureDetail)
                : "未检测到 yt-dlp 或 you-get，当前只能下载公开直链媒体。请安装并验证其中一个工具后重试。";
        return FetchResult.fail(available, ytdlpAvailable() || yougetAvailable() ? "TOOL_FAILED" : "TOOL_MISSING", "video-page", null);
    }

    // ==================== 图片图集抓取 ====================

    /** gallery-dl ships inside the bundled venv; see {@link #ytdlpCmd()} for why it runs as a module. */
    private List<String> gallerydlCmd() {
        return "gallery-dl".equals(props.getGallerydlPath())
                ? List.of(props.localPythonPath(), "-m", "gallery_dl")
                : List.of(props.getGallerydlPath());
    }

    public boolean gallerydlAvailable() {
        return runner.available(gallerydlCmd(), "--version");
    }

    /**
     * 从图集/图片页面抓取图片（gallery-dl 驱动，支持 Pinterest、微博、Twitter/X 媒体、多数公开图集站点）。
     * 返回结果中的 imagePaths 为该次下载的全部新图片，filePath 指向首个图片。
     */
    public FetchResult fetchImages(String url) {
        final String validatedUrl;
        try {
            validatedUrl = UrlGuard.validate(url);
        } catch (IllegalArgumentException e) {
            return FetchResult.fail("URL 格式错误或目标地址不允许访问: " + safeUrlError(e),
                    "URL_GUARD_REJECTED", null, null);
        }
        if (!gallerydlAvailable()) {
            return FetchResult.fail("未检测到 gallery-dl，当前无法抓取图片图集。请安装并验证 gallery-dl 后重试。",
                    "TOOL_MISSING", "image-gallery", null);
        }
        Path dir = props.downloads();
        String stamp = String.valueOf(System.currentTimeMillis());
        Path target = dir.resolve(stamp + "_gallery");
        Set<Path> before = snapshotFiles(dir);
        List<String> cmd = new ArrayList<>(gallerydlCmd());
        cmd.addAll(List.of(
                "--directory", target.toString(),
                "--no-part",
                "--range", "1-100",
                "-q", validatedUrl));
        ProcRunner.Result r = runner.run(cmd, 1800);
        List<Path> images = newImages(before, dir);
        if (images.isEmpty()) {
            return FetchResult.fail(r.ok() ? "gallery-dl 未下载到任何图片，请确认链接公开可访问且为图片内容" : describeToolFailure("gallery-dl", r),
                    r.ok() ? "NO_IMAGES" : "TOOL_FAILED", "image-gallery", null);
        }
        FetchResult fr = new FetchResult();
        fr.ok = true;
        fr.filePath = images.get(0).toString();
        fr.imagePaths = images.stream().map(Path::toString).collect(java.util.stream.Collectors.toList());
        fr.message = "成功下载 " + images.size() + " 张图片";
        fr.via = "gallery-dl";
        return fr;
    }

    // ==================== 音频素材检索 ====================

    /**
     * @param source freesound / mixkit / ear0 / tosound / all
     */
    public List<RemoteItem> searchAudio(String source, String keyword, int limit) {
        return searchAudio(source, keyword, limit, null);
    }

    public List<RemoteItem> searchAudio(String source, String keyword, int limit, Project project) {
        List<RemoteItem> out = new ArrayList<>();
        String s = source == null ? "all" : source.toLowerCase();
        String mappedKeyword = String.join(" ", audioTerms(keyword));
        try {
            if (s.equals("all") || s.equals("freesound")) out.addAll(freesound(mappedKeyword, limit));
            if (s.equals("all") || s.equals("mixkit")) out.addAll(mixkit(keyword, limit));
            if (s.equals("all") || s.equals("wikimedia")) out.addAll(wikimedia(mappedKeyword, limit));
            if (s.equals("all") || s.equals("archive")) out.addAll(internetArchive(mappedKeyword, limit));
            if (s.equals("tosound")) {
                // toSound 当前公开搜索页不要求应用代登录；只解析公开页面里的媒体地址。
                out.addAll(publicSiteSearch(s, keyword, limit));
            } else if (s.equals("ear0")) {
                if (!props.isAllowLoginCrawl()) {
                    RemoteItem tip = notice(s,
                            "[已按合规策略关闭] " + s + " 需要登录态。请先确认官方授权，再按页面说明配置 APP_ALLOW_LOGIN_CRAWL=true；应用不会读取 Cookie 或密码。",
                            "https://www.ear0.com/", "APP_ALLOW_LOGIN_CRAWL");
                    tip.setLicense("blocked");
                    out.add(tip);
                } else {
                    out.addAll(loginSiteSearch(s, keyword, limit));
                }
            }

            // 缺 Key 时给出可见提示，而不是静默返回空列表让用户以为"搜不到"
            boolean wantFreesound = s.equals("all") || s.equals("freesound");
            if (wantFreesound && isBlank(props.getFreesoundApiKey())) {
                out.add(notice("freesound",
                        "[未配置] Freesound 需要 API Key：打开官方申请页，配置 APP_FREESOUND_API_KEY 后重启后端。",
                        "https://freesound.org/apiv2/apply/", "APP_FREESOUND_API_KEY"));
            }
        } catch (Exception e) {
            log.warn("searchAudio failed: {}", safeUrlError(e));
            out.add(notice(s, "[检索失败] " + e.getClass().getSimpleName() + ": " + safeUrlError(e)));
        }
        return rankForProject(out, keyword, project, limit * 3);
    }

    /**
     * Only return publicly downloadable videos with a visible license. Pixabay requires
     * the user's own API key; the endpoint intentionally reports that prerequisite.
     */
    public List<RemoteItem> searchVideo(String source, String keyword, int limit) {
        return searchVideo(source, keyword, limit, null);
    }

    /** Same safe search contract with a short per-source timeout for background Studio auto-fill. */
    public List<RemoteItem> searchPublicVideoQuick(String source, String keyword, int limit, Project project) {
        QUICK_PUBLIC_SEARCH.set(true);
        try {
            return searchVideo(source, keyword, limit, project);
        } finally {
            QUICK_PUBLIC_SEARCH.remove();
        }
    }

    public List<RemoteItem> searchVideo(String source, String keyword, int limit, Project project) {
        List<RemoteItem> out = new ArrayList<>();
        String s = source == null ? "all" : source.toLowerCase(Locale.ROOT);
        String mappedKeyword = String.join(" ", videoTerms(keyword));
        // Commons and Archive have predominantly English metadata. Keep the original keyword for
        // relevance ranking, but query their public indexes with a compact mapped intent phrase.
        String publicSourceKeyword = publicVideoSearchKeyword(keyword);
        try {
            if (s.equals("all") || s.equals("pixabay")) {
                if (isBlank(props.getPixabayApiKey())) {
                    if (s.equals("pixabay")) {
                        out.add(notice("pixabay", "[未配置] Pixabay 视频检索需要 API Key：打开官方文档，配置 APP_PIXABAY_API_KEY 后重启后端。",
                                "https://pixabay.com/api/docs/", "APP_PIXABAY_API_KEY"));
                    }
                } else {
                    out.addAll(pixabay(mappedKeyword, limit));
                }
            }
            if (s.equals("all") || s.equals("pexels")) {
                if (isBlank(props.getPexelsApiKey())) {
                    if (s.equals("pexels")) {
                        out.add(notice("pexels", "[未配置] Pexels 视频检索需要 API Key：打开官方申请页，配置 APP_PEXELS_API_KEY 后重启后端。",
                                "https://www.pexels.com/api/", "APP_PEXELS_API_KEY"));
                    }
                } else {
                    out.addAll(pexels(mappedKeyword, limit));
                }
            }
            if (s.equals("all") || s.equals("wikimedia")) out.addAll(wikimedia(publicSourceKeyword, limit, "video"));
            if (s.equals("all") || s.equals("archive")) out.addAll(internetArchive(publicSourceKeyword, limit, "video"));
        } catch (Exception e) {
            log.warn("searchVideo failed: {}", safeUrlError(e));
            out.add(notice(s, "[检索失败] " + e.getClass().getSimpleName() + ": " + safeUrlError(e)));
        }
        return rankForProject(out, keyword, project, limit * 3);
    }

    /**
     * Revalidates browser-provided search rows at the server boundary before a
     * long-running import task is persisted. The browser never decides which
     * source is importable, nor whether a URL is safe to dereference.
     */
    public RemoteItem validateRemoteItem(RemoteItem item, String expectedType) {
        if (item == null || item.isNotice()) {
            throw new IllegalArgumentException("请选择真实的公开素材条目，而不是来源提示");
        }
        String type = expectedType == null ? "" : expectedType.trim().toLowerCase(Locale.ROOT);
        if (!"audio".equals(type) && !"video".equals(type)) {
            throw new IllegalArgumentException("不支持的公开素材类型");
        }
        if (!type.equalsIgnoreCase(item.getType())) {
            throw new IllegalArgumentException("公开素材类型与导入任务不一致");
        }
        String source = item.getSource() == null ? "" : item.getSource().trim().toLowerCase(Locale.ROOT);
        if (!("audio".equals(type) ? supportsAudioSource(source) : supportsVideoSource(source))) {
            throw new IllegalArgumentException("该来源当前未接入受控" + ("audio".equals(type) ? "音频" : "视频") + "导入");
        }
        if ("notice".equalsIgnoreCase(item.getLicense()) || "blocked".equalsIgnoreCase(item.getLicense())) {
            throw new IllegalArgumentException("该条目不是可导入的公开媒体");
        }
        item.setDownloadUrl(UrlGuard.validate(item.getDownloadUrl()));
        item.setSource(source);
        item.setType(type);
        return item;
    }

    public boolean supportsAudioSource(String source) {
        return Set.of("freesound", "mixkit", "wikimedia", "archive", "tosound", "ear0").contains(source);
    }

    public boolean supportsVideoSource(String source) {
        return Set.of("pixabay", "pexels", "wikimedia", "archive").contains(source);
    }

    private List<RemoteItem> pixabay(String kw, int limit) {
        List<RemoteItem> list = new ArrayList<>();
        String key = props.getPixabayApiKey();
        if (key == null || key.isBlank()) return list;
        String url = "https://pixabay.com/api/videos/?key=" + key
                + "&q=" + enc(kw) + "&per_page=" + Math.min(50, Math.max(3, limit));
        // Pixabay 音乐无公开 API，这里返回视频素材；音乐走 mixkit / freesound
        JsonNode root = getJson(url);
        if (root == null) return list;
        for (JsonNode h : root.path("hits")) {
            RemoteItem it = new RemoteItem();
            it.setSource("pixabay");
            it.setType("video");
            it.setTitle(h.path("tags").asText("pixabay-" + h.path("id").asInt()));
            it.setPageUrl(h.path("pageURL").asText());
            it.setDownloadUrl(h.path("videos").path("medium").path("url").asText(
                    h.path("videos").path("small").path("url").asText("")));
            it.setDuration(h.path("duration").asDouble(0));
            it.setLicense("Pixabay Content License (免费商用)");
            if (!it.getDownloadUrl().isBlank()) list.add(it);
        }
        return list;
    }

    // ==================== Pexels 视频检索（官方 API） ====================

    /**
     * Pexels 视频检索。只走官方 API（api.pexels.com/v1/videos/search），不做任何网页抓取；
     * API Key 仅从服务端环境变量 APP_PEXELS_API_KEY 读取，通过 Authorization 请求头传递，
     * 不拼进 URL、不写入日志、不返回给前端。
     *
     * 检索定向为竖屏（portrait）并优先挑选竖版 HD 的 mp4 直链，与抖音竖版画布匹配；
     * 下载仍统一走 UrlGuard + downloadTo（fetchRemoteItem），媒体直链不绕过 SSRF 校验。
     */
    private List<RemoteItem> pexels(String kw, int limit) {
        List<RemoteItem> list = new ArrayList<>();
        String key = props.getPexelsApiKey();
        if (key == null || key.isBlank()) return list;
        HttpResult r = httpGet(pexelsSearchUrl(kw, limit), key);
        if (r.body() == null || r.body().isBlank()) {
            RemoteItem err = pexelsErrorNotice(r.code());
            if (err != null) {
                list.add(err);
                return list;
            }
            // code <= 0：连接层失败（超时/域名解析/被 SSRF 拦截），日志已记录，返回空列表由调用方兜底
            return list;
        }
        final JsonNode root;
        try {
            root = om.readTree(r.body());
        } catch (Exception e) {
            log.warn("Pexels API response could not be parsed");
            return list;
        }
        for (JsonNode video : root.path("videos")) {
            RemoteItem it = mapPexelsVideo(video);
            if (it == null || isBlank(it.getDownloadUrl())) continue;
            list.add(it);
            if (list.size() >= limit) break;
        }
        return list;
    }

    /** 构建 Pexels 官方检索 URL（包内可见以便聚焦单测锁定参数与编码）。 */
    String pexelsSearchUrl(String kw, int limit) {
        return "https://api.pexels.com/v1/videos/search?query=" + enc(kw)
                + "&per_page=" + Math.min(80, Math.max(3, limit))
                + "&orientation=portrait";
    }

    /**
     * Pexels 失败状态码 → 可操作的提示条目（包内可见以便聚焦单测）；
     * 返回 null 表示连接层失败（无需向用户展示，日志已记录）。
     */
    static RemoteItem pexelsErrorNotice(int code) {
        if (code == 429) {
            return notice("pexels", "[限流] Pexels API 请求过于频繁（HTTP 429）：请稍后重试；免费额度与配额见官方控制台。",
                    "https://www.pexels.com/api/", "APP_PEXELS_API_KEY");
        }
        if (code == 401 || code == 403) {
            return notice("pexels", "[鉴权失败] Pexels API Key 无效或已被拒绝（HTTP " + code + "）：请到官方控制台重新申请并更新 APP_PEXELS_API_KEY 后重启后端。",
                    "https://www.pexels.com/api/", "APP_PEXELS_API_KEY");
        }
        if (code > 0) {
            return notice("pexels", "[检索失败] Pexels API 请求失败（HTTP " + code + "）：请稍后重试；持续失败请检查网络或到官方控制台确认配额。",
                    "https://www.pexels.com/api/", "APP_PEXELS_API_KEY");
        }
        return null;
    }

    /**
     * 把 Pexels 搜索结果条目映射为 RemoteItem：pageUrl 指向视频详情页，downloadUrl 指向
     * 挑好的直链，creator 放入 tags（Pexels 官方许可无需署名，但保留创作者元数据便于核验）。
     */
    static RemoteItem mapPexelsVideo(JsonNode video) {
        if (video == null || !video.isObject()) return null;
        RemoteItem it = new RemoteItem();
        it.setSource("pexels");
        it.setType("video");
        long id = video.path("id").asLong(0);
        String creatorName = video.path("user").path("name").asText("");
        String creatorUrl = video.path("user").path("url").asText("");
        it.setTitle((creatorName.isBlank() ? "Pexels" : "Pexels · " + creatorName) + " #" + id);
        String page = video.path("url").asText("");
        it.setPageUrl(page.isBlank() ? "https://www.pexels.com/video/" + id + "/" : page);
        it.setTags(creatorName.isBlank() && creatorUrl.isBlank() ? ""
                : creatorName.isBlank() ? creatorUrl
                : creatorUrl.isBlank() ? creatorName : creatorName + " · " + creatorUrl);
        double duration = video.path("duration").asDouble(0);
        it.setDuration(duration > 0 ? duration : null);
        it.setLicense("Pexels License (免费商用)");
        it.setLicenseUrl("https://www.pexels.com/license/");
        it.setDownloadUrl(pickPexelsRendition(video.path("video_files")));
        return it;
    }

    /**
     * 从 Pexels 的 video_files 里挑一条竖版、mp4、HD 优先的直链：
     * mp4 优先于其它封装；存在竖版（高 &gt; 宽）时只在竖版里挑，与 portrait 检索配合；
     * 质量偏好 hd &gt; uhd &gt; sd（uhd 文件过大，不适合素材库批量导入），同级取分辨率最高。
     */
    static String pickPexelsRendition(JsonNode videoFiles) {
        if (videoFiles == null || !videoFiles.isArray()) return "";
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode f : videoFiles) {
            if (f.isObject() && !f.path("link").asText("").isBlank()) candidates.add(f);
        }
        if (candidates.isEmpty()) return "";
        List<JsonNode> mp4 = candidates.stream()
                .filter(f -> "video/mp4".equalsIgnoreCase(f.path("file_type").asText("")))
                .toList();
        if (!mp4.isEmpty()) candidates = mp4;
        List<JsonNode> vertical = candidates.stream()
                .filter(f -> f.path("height").asInt(0) > f.path("width").asInt(0))
                .toList();
        if (!vertical.isEmpty()) candidates = vertical;
        return candidates.stream()
                .sorted(Comparator.comparingInt((JsonNode f) -> pexelsQualityRank(f))
                        .thenComparing(Comparator.comparingLong((JsonNode f) ->
                                (long) f.path("width").asInt(0) * f.path("height").asInt(0)).reversed()))
                .map(f -> f.path("link").asText(""))
                .filter(link -> !link.isBlank())
                .findFirst().orElse("");
    }

    private static int pexelsQualityRank(JsonNode file) {
        String q = file.path("quality").asText("").toLowerCase(Locale.ROOT);
        if ("hd".equals(q)) return 0;
        if ("uhd".equals(q)) return 1;
        if ("sd".equals(q)) return 2;
        return 3;
    }

    private List<RemoteItem> freesound(String kw, int limit) {
        List<RemoteItem> list = new ArrayList<>();
        String key = props.getFreesoundApiKey();
        if (key == null || key.isBlank()) return list;
        String url = "https://freesound.org/apiv2/search/text/?query=" + enc(kw)
                + "&page_size=" + Math.min(50, Math.max(3, limit))
                + "&fields=id,name,duration,license,previews,url"
                + "&token=" + key;
        JsonNode root = getJson(url);
        if (root == null) return list;
        for (JsonNode h : root.path("results")) {
            RemoteItem it = new RemoteItem();
            it.setSource("freesound");
            it.setType("audio");
            it.setTitle(h.path("name").asText());
            it.setPageUrl(h.path("url").asText());
            it.setDuration(h.path("duration").asDouble(0));
            it.setLicense(h.path("license").asText());
            String prev = h.path("previews").path("preview-hq-mp3").asText(
                    h.path("previews").path("preview-lq-mp3").asText(""));
            it.setPreviewUrl(prev);
            it.setDownloadUrl(prev);
            if (!prev.isBlank()) list.add(it);
        }
        return list;
    }

    /** Wikimedia Commons：仅检索白名单许可媒体，下载仍统一走 UrlGuard + downloadTo。 */
    private List<RemoteItem> wikimedia(String kw, int limit) {
        return wikimedia(kw, limit, "audio");
    }

    /**
     * 构建 MediaWiki API 查询串（包内可见以便聚焦单测锁定编码回归）。
     *
     * <p>iiprop 是多值参数，分隔符是 {@code |}。查询串里必须用编码形式
     * {@code %7C}（既不能裸写 {@code |}，也不能二次编码成 {@code %257C}），
     * MediaWiki 不会按逗号拆分该参数。</p>
     */
    String wikimediaQuery(String kw, String type, int limit) {
        return wikimediaAdapter.query(kw, type, limit);
    }

    private List<RemoteItem> wikimedia(String kw, int limit, String type) {
        String query = wikimediaQuery(kw, type, limit);
        JsonNode root = QUICK_PUBLIC_SEARCH.get()
                ? getJsonQuick("https://commons.wikimedia.org/w/api.php?" + query)
                : getJson("https://commons.wikimedia.org/w/api.php?" + query);
        return wikimediaAdapter.map(root, type, limit);
    }

    /** Internet Archive：只接受带 CC0 / 公有领域 / CC BY 白名单许可声明的媒体文件。 */
    private List<RemoteItem> internetArchive(String kw, int limit) {
        return internetArchive(kw, limit, "audio");
    }

    private List<RemoteItem> internetArchive(String kw, int limit, String type) {
        RemoteSourceAdapter.JsonFetcher fetcher = url -> QUICK_PUBLIC_SEARCH.get() ? getJsonQuick(url) : getJson(url);
        return archiveAdapter.search(kw, type, limit, fetcher);
    }

    /**
     * 免登录自动源的许可白名单：仅 CC0 / Public Domain / CC BY（署名）。
     * CC BY-SA / CC BY-NC / CC BY-ND 以及任何未标注许可的条目一律不进入自动填充。
     * 兼容短名（LicenseShortName）与 archive.org 的 licenseurl 链接两种形态。
     */
    private static final Pattern CC_BY_ONLY = Pattern.compile(
            "\\bcc[\\s_-]?by(?!\\s*[-_\\s]?(sa|nc|nd))", Pattern.CASE_INSENSITIVE);
    private static final Pattern LICENSES_BY_ONLY = Pattern.compile(
            "licenses/by(?![-_]?(sa|nc|nd))", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTION_ONLY = Pattern.compile(
            "\\battribution(?!\\s*[-_]?(share|non|no))", Pattern.CASE_INSENSITIVE);

    static boolean isWhitelistedLicense(String value) {
        if (value == null || value.isBlank()) return false;
        String v = value.toLowerCase(Locale.ROOT);
        // CC0（含 archive.org publicdomain/zero 链接与 "CC0 1.0" 短名）
        if (v.contains("cc0") || v.contains("cc 0") || v.contains("publicdomain/zero")) return true;
        // 公有领域（Public Domain / PD Mark / archive.org publicdomain/mark 链接）
        if (v.contains("public domain") || v.contains("publicdomain") || v.contains("cc-pd") || v.contains("cc pd")) return true;
        // CC BY 仅署名，排除派生/非商用/禁止演绎变体
        if (CC_BY_ONLY.matcher(v).find()) return true;
        if (LICENSES_BY_ONLY.matcher(v).find()) return true;
        if (ATTRIBUTION_ONLY.matcher(v).find()) return true;
        return false;
    }

    /** 把 archive.org 的 licenseurl 或原始许可字符串映射为可读短标签（可核验的许可元数据）。 */
    static String licenseLabel(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String v = raw.toLowerCase(Locale.ROOT);
        if (v.contains("publicdomain/zero") || v.contains("cc0")) return "CC0";
        if (v.contains("publicdomain") || v.contains("public domain")) return "Public Domain";
        if (v.contains("licenses/by/")) {
            Matcher m = Pattern.compile("licenses/by/(\\d\\.\\d)").matcher(v);
            return m.find() ? "CC BY " + m.group(1) : "CC BY";
        }
        return raw.trim();
    }

    /**
     * 占位/示例媒体文件名检测：只作用于免登录自动源，避免把站点自带的示例文件
     * （如 Commons 的 Demo.ogg / Example.jpg）静默排入无人值守任务队列。
     */
    static boolean isDemoPlaceholderTitle(String title) {
        if (title == null || title.isBlank()) return false;
        String name = title.replaceFirst("^File:", "");
        int i = name.lastIndexOf('.');
        if (i > 0) name = name.substring(0, i);
        name = name.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return false;
        if (Set.of("demo", "example", "placeholder", "sample", "test").contains(name)) return true;
        return Pattern.compile("\\b(demo|example|placeholder|sample)\\b").matcher(name).find();
    }

    // Mixkit 条目卡片解析用的正则（页面为服务端渲染，字段稳定）
    private static final Pattern MK_CARD_SPLIT = Pattern.compile("class=\"item-grid__item\"");
    private static final Pattern MK_URL   = Pattern.compile("preview-url-value=\"(https://assets\\.mixkit\\.co/music/\\d+/[^\"]+\\.mp3)\"");
    private static final Pattern MK_DUR   = Pattern.compile("data-test-id=\"duration\">\\s*(\\d+):(\\d+)\\s*<");
    private static final Pattern MK_TAG   = Pattern.compile("class=\"meta-links__link\"[^>]*>([^<]+)</a>");

    /**
     * Mixkit 免费商用音乐。
     *
     * 仅用于手动导入（用户在抓取页检索并逐条确认）；Mixkit 无公开 API 且服务条款不支持
     * 无人值守抓取，自动填充（MaterialGapService.autoFill）一律不调用本路径。
     *
     * 注意：mixkit 列表页里没有曲名，只有 音乐ID + 时长 + 风格/情绪/乐器标签。
     * 早期实现直接用文件名（"644"）当标题，前端素材列表全是数字，选曲根本没法用。
     * 现在改为按卡片解析，用标签拼出可读标题（如 "Hip Hop · Relaxed · Positive"），
     * 并把时长带出来 —— 选 BGM 时时长是关键决策信息。
     *
     * 另外 /tag/xxx/ 已 301 到 /discover/xxx/，且分类页只有 1 条直链，
     * 所以统一抓主列表页，再用标签在本地做关键词过滤，命中不足时回退全量。
     */
    private static final Map<String, List<String>> AUDIO_INTENTS = Map.ofEntries(
            Map.entry("人文", List.of("documentary", "world", "ambient", "cinematic", "ethnic", "geography")),
            Map.entry("纪录片", List.of("documentary", "world", "ambient", "cinematic")),
            Map.entry("科技", List.of("technology", "electronic", "future", "minimal", "pulse")),
            Map.entry("测评", List.of("technology", "electronic", "minimal", "pulse")),
            Map.entry("开箱", List.of("upbeat", "energetic", "pop", "beat", "hip hop")),
            Map.entry("卡点", List.of("upbeat", "energetic", "beat", "hip hop", "electronic")),
            Map.entry("护肤", List.of("beauty", "soft", "elegant", "chill", "relaxed")),
            Map.entry("美妆", List.of("beauty", "soft", "elegant", "chill", "pop")),
            Map.entry("温馨", List.of("warm", "acoustic", "children", "gentle", "carefree")),
            Map.entry("母婴", List.of("warm", "acoustic", "children", "gentle")),
            Map.entry("轻快", List.of("upbeat", "happy", "carefree", "pop", "energetic")),
            Map.entry("舒缓", List.of("calm", "relaxed", "ambient", "soft", "chill")),
            Map.entry("食品", List.of("food", "cooking", "kitchen", "delicious", "taste", "snack")),
            Map.entry("美食", List.of("food", "cooking", "kitchen", "restaurant", "delicious", "taste")),
            Map.entry("探店", List.of("restaurant", "food", "cafe", "shopping", "lifestyle")),
            Map.entry("零食", List.of("snack", "food", "packaged food", "taste", "kitchen")),
            Map.entry("饮料", List.of("drink", "beverage", "refreshment", "bottle", "pouring")),
            Map.entry("餐饮", List.of("restaurant", "food", "cooking", "kitchen", "serving")),
            Map.entry("家居", List.of("home", "interior", "lifestyle", "cleaning", "living room")),
            Map.entry("服装", List.of("fashion", "clothing", "style", "outfit", "apparel")),
            Map.entry("鞋", List.of("shoes", "fashion", "walking", "footwear", "style")),
            Map.entry("汽车", List.of("car", "driving", "vehicle", "road", "automotive")),
            Map.entry("宠物", List.of("pet", "dog", "cat", "animal", "care")),
            Map.entry("运动", List.of("fitness", "sport", "training", "workout", "active")),
            Map.entry("旅行", List.of("travel", "landscape", "destination", "tourism", "journey")),
            Map.entry("教育", List.of("education", "learning", "study", "classroom", "book")),
            Map.entry("办公", List.of("office", "work", "desk", "business", "laptop")),
            Map.entry("清洁", List.of("cleaning", "home", "hygiene", "wash", "kitchen")),
            Map.entry("保健品", List.of("health", "wellness", "supplement", "nutrition")),
            Map.entry("香水", List.of("perfume", "fragrance", "scent", "elegant", "luxury")),
            Map.entry("数码", List.of("digital", "gadget", "tech", "unboxing", "device"))
    );

    private List<String> audioTerms(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String clean = keyword.trim().toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        terms.add(clean);
        AUDIO_INTENTS.forEach((zh, mapped) -> { if (clean.contains(zh)) terms.addAll(mapped); });
        return terms.stream().distinct().toList();
    }

    /** Pixabay accepts free-text video queries; reuse the same Chinese intent expansion. */
    private List<String> videoTerms(String keyword) {
        return audioTerms(keyword);
    }

    /**
     * Public no-login video indexes are primarily English. Convert Chinese product intent to a
     * short English phrase for their query syntax, while the original request remains available
     * to {@link #rankForProject(List, String, Project, int)} for relevance accounting.
     */
    public String publicVideoSearchKeyword(String keyword) {
        String clean = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        // Map.ofEntries iteration order is unspecified; public search must choose a stable primary intent.
        for (String intent : List.of("美食", "探店", "食品", "零食", "饮料", "餐饮", "护肤", "美妆", "母婴", "家居", "服装", "鞋", "汽车", "宠物", "运动", "旅行", "教育", "办公", "清洁", "数码", "保健品", "香水", "科技", "测评", "开箱", "纪录片", "人文", "温馨", "轻快", "舒缓", "卡点")) {
            if (clean.contains(intent)) return AUDIO_INTENTS.get(intent).get(0);
        }
        return java.util.Arrays.stream(clean.split("\\s+"))
                .filter(term -> term.matches("[a-z0-9][a-z0-9_-]*"))
                .findFirst()
                .orElse("lifestyle b-roll");
    }

    private List<RemoteItem> mixkit(String kw, int limit) {
        String url = "https://mixkit.co/free-stock-music/";
        String html = getText(url);
        if (html == null) return List.of();

        List<RemoteItem> all = new ArrayList<>();
        String[] cards = MK_CARD_SPLIT.split(html);
        for (int i = 1; i < cards.length; i++) {
            String c = cards[i];
            Matcher mu = MK_URL.matcher(c);
            if (!mu.find()) continue;
            String mp3 = mu.group(1);

            double dur = 0;
            Matcher md = MK_DUR.matcher(c);
            if (md.find()) dur = Integer.parseInt(md.group(1)) * 60 + Integer.parseInt(md.group(2));

            List<String> tags = new ArrayList<>();
            Matcher mt = MK_TAG.matcher(c);
            while (mt.find()) {
                String t = unescapeHtml(mt.group(1).trim());
                if (!t.isEmpty() && !tags.contains(t)) tags.add(t);
            }

            RemoteItem it = new RemoteItem();
            it.setSource("mixkit");
            it.setType("audio");
            it.setTitle(tags.isEmpty()
                    ? "Mixkit #" + idOf(mp3)
                    : String.join(" · ", tags.subList(0, Math.min(3, tags.size()))) + "  #" + idOf(mp3));
            it.setPageUrl(url);
            it.setDownloadUrl(mp3);
            it.setPreviewUrl(mp3);
            it.setDuration(dur > 0 ? dur : null);
            it.setLicense("Mixkit Free License (免费商用)");
            it.setTags(String.join(",", tags));
            all.add(it);
        }

        // 中文意图先映射为公开音乐站常用英文标签；没有命中则明确返回空，绝不悄悄换成随机音乐。
        List<RemoteItem> hit = all;
        if (kw != null && !kw.isBlank()) {
            List<String> terms = audioTerms(kw);
            hit = all.stream()
                    .filter(x -> {
                        String tags = x.getTags() == null ? "" : x.getTags().toLowerCase(Locale.ROOT);
                        return terms.stream().anyMatch(tags::contains);
                    })
                    .sorted(Comparator.comparingInt((RemoteItem x) -> {
                        String tags = x.getTags() == null ? "" : x.getTags().toLowerCase(Locale.ROOT);
                        return audioTerms(kw).stream().anyMatch(tags::contains) ? 0 : 1;
                    }))
                    .toList();
            if (hit.isEmpty()) {
                return List.of();
            }
        }
        return hit.size() > limit ? new ArrayList<>(hit.subList(0, limit)) : hit;
    }

    List<RemoteItem> rankForProject(List<RemoteItem> items, String keyword, Project project, int max) {
        if (items == null || items.isEmpty()) return List.of();
        List<String> queryTerms = audioTerms(keyword);
        Set<String> projectTerms = project == null ? Set.of() : projectTerms(project);
        Set<String> bannedTerms = project == null ? Set.of() : splitTerms(project.getBannedWords());
        List<RemoteItem> ranked = new ArrayList<>();
        for (RemoteItem item : items) {
            if (item == null || item.isNotice()) {
                ranked.add(item);
                continue;
            }
            String text = String.join(" ", String.valueOf(item.getTitle()), String.valueOf(item.getTags()), String.valueOf(item.getPageUrl()))
                    .toLowerCase(Locale.ROOT);
            if (!bannedTerms.isEmpty() && bannedTerms.stream().anyMatch(text::contains)) continue;
            LinkedHashSet<String> hits = new LinkedHashSet<>();
            queryTerms.stream().filter(term -> !term.isBlank() && text.contains(term.toLowerCase(Locale.ROOT))).forEach(hits::add);
            projectTerms.stream().filter(term -> !term.isBlank() && text.contains(term.toLowerCase(Locale.ROOT))).forEach(hits::add);
            if (project != null && hits.isEmpty()) continue;
            item.setProjectId(project == null ? null : project.getId());
            item.setHitKeywords(String.join(",", hits));
            item.setRelevanceScore(hits.size());
            ranked.add(item);
        }
        ranked.sort(Comparator.comparing((RemoteItem item) -> item == null || item.getRelevanceScore() == null ? -1 : item.getRelevanceScore()).reversed());
        return ranked.size() > max ? new ArrayList<>(ranked.subList(0, max)) : ranked;
    }

    private Set<String> projectTerms(Project project) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String value : new String[]{project.getBrand(), project.getCategory(), project.getProduct(), project.getSellingPoints(), project.getAudience()}) {
            terms.addAll(splitTerms(value));
        }
        terms.removeIf(term -> term.length() < 2 || term.matches("[0-9]+"));
        return terms;
    }

    private Set<String> splitTerms(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[\\s,，、;；/|]+"))
                .map(String::trim).filter(term -> !term.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String idOf(String mp3Url) {
        Matcher m = Pattern.compile("/music/(\\d+)/").matcher(mp3Url);
        return m.find() ? m.group(1) : "?";
    }

    private static String unescapeHtml(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    /** 公开搜索站点：只解析无需登录即可访问的结果页，不使用 Cookie 或账号密码。 */
    private List<RemoteItem> publicSiteSearch(String site, String kw, int limit) {
        String url = "https://www.tosound.com/?s=" + enc(kw);
        return parsePublicAudioPage(site, url, kw, limit, "请自行确认授权");
    }

    /** 需登录站点：仅在明确开启后走通用页面直链解析 */
    private List<RemoteItem> loginSiteSearch(String site, String kw, int limit) {
        String url = site.equals("ear0")
                ? "https://www.ear0.com/sound/search?keyword=" + enc(kw)
                : "http://www.tosound.com/?s=" + enc(kw);
        return parsePublicAudioPage(site, url, kw, limit, "请自行确认授权");
    }

    private List<RemoteItem> parsePublicAudioPage(String site, String url, String kw, int limit, String license) {
        String html = getText(url);
        List<RemoteItem> list = new ArrayList<>();
        if (html == null) return list;
        Matcher m = Pattern.compile("https?://[^\"'\\s]+?\\.(mp3|wav|m4a)").matcher(html);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && seen.size() < limit) seen.add(m.group());
        for (String u : seen) {
            RemoteItem it = new RemoteItem();
            it.setSource(site);
            it.setType("audio");
            it.setTitle(prettyName(fileNameOf(u)));
            it.setDownloadUrl(u);
            it.setPreviewUrl(u);
            it.setPageUrl(url);
            it.setLicense(license);
            list.add(it);
        }
        return list;
    }

    /** 把检索结果落地成本地文件 */
    public FetchResult fetchRemoteItem(RemoteItem it) {
        if (it == null || it.getDownloadUrl() == null || it.getDownloadUrl().isBlank()) {
            return FetchResult.fail("该条目没有可下载的公开媒体地址。请换一个检索结果或导入已授权本地文件。",
                    "NO_DOWNLOAD_URL", it == null ? null : it.getSource(), null);
        }
        if (it.isNotice() || "notice".equals(it.getLicense()) || "blocked".equals(it.getLicense())) {
            return FetchResult.fail("该条目是来源提示，不是可下载素材。请按提示完成授权配置或选择其他公开条目。",
                    "SOURCE_NOTICE", it.getSource(), null);
        }
        final String downloadUrl;
        try {
            // pageUrl is metadata only; downloadUrl is the only URL dereferenced here.
            downloadUrl = UrlGuard.validate(it.getDownloadUrl());
        } catch (IllegalArgumentException e) {
            return FetchResult.fail("下载地址格式错误或目标地址不允许访问: " + safeUrlError(e),
                    "URL_GUARD_REJECTED", it.getSource(), null);
        }
        String ext = extOf(downloadUrl);
        if (ext.isBlank()) ext = "audio".equals(it.getType()) ? ".mp3" : ".mp4";
        String base = safeName(it.getTitle() == null ? "material" : it.getTitle());
        if (base.length() > 48) base = base.substring(0, 48);
        Path dst = props.downloads().resolve(System.currentTimeMillis() + "_" + base + ext);
        DownloadResult download = downloadTo(downloadUrl, dst);
        if (download.ok()) {
            FetchResult r = new FetchResult();
            r.ok = true;
            r.filePath = dst.toString();
            r.via = it.getSource();
            return r;
        }
        return FetchResult.fail(download.message(), download.errorCode(), it.getSource(), download.httpStatus());
    }

    // ==================== 基础 HTTP ====================

    private DownloadResult downloadTo(String url, Path dst) {
        HttpURLConnection conn = null;
        String current = null;
        try {
            current = UrlGuard.validate(url);
            for (int redirects = 0; redirects <= 3; redirects++) {
                conn = openGetWithTransientRetry(current, 300000);
                int code = conn.getResponseCode();
                if (isRedirect(code)) {
                    String next = redirectTarget(current, conn.getHeaderField("Location"));
                    conn.disconnect();
                    conn = null;
                    if (next == null) {
                        return DownloadResult.fail("下载地址重定向无效或被安全策略拒绝。请换用公开的直接媒体链接。",
                                "REDIRECT_REJECTED", code);
                    }
                    current = next;
                    continue;
                }
                if (code >= 300) {
                    log.warn("download http {} for {}", code, safeUrl(current));
                    return DownloadResult.fail(httpFailureMessage(code), httpFailureCode(code), code);
                }
                String contentType = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase(Locale.ROOT);
                if (contentType.contains("text/html") || contentType.contains("application/json") || contentType.contains("text/plain")) {
                    return DownloadResult.fail("链接返回的是网页或接口响应，不是媒体文件。请复制公开的图片/音频/视频直链。", "NOT_MEDIA_CONTENT", code);
                }
                Files.createDirectories(dst.getParent());
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(dst)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (Files.size(dst) <= 1024) {
                    Files.deleteIfExists(dst);
                    return DownloadResult.fail("下载内容为空或过小，可能不是可用媒体文件。请换用来源提供的媒体直链。",
                            "EMPTY_OR_TOO_SMALL", code);
                }
                return DownloadResult.success();
            }
            log.warn("download rejected after too many redirects for {}", safeUrl(current));
            return DownloadResult.fail("下载地址重定向次数过多。请换用稳定的公开媒体链接。", "TOO_MANY_REDIRECTS", null);
        } catch (IllegalArgumentException e) {
            return DownloadResult.fail("下载地址格式错误或目标地址不允许访问: " + safeUrlError(e),
                    "URL_GUARD_REJECTED", null);
        } catch (java.net.SocketTimeoutException e) {
            log.warn("download timed out for {}", safeUrl(current == null ? url : current));
            return DownloadResult.fail("来源响应超时。请稍后重试、切换来源或导入本地素材。", "TIMEOUT", null);
        } catch (IOException e) {
            log.warn("download network failure for {}: {}", safeUrl(current == null ? url : current), safeUrlError(e));
            return DownloadResult.fail("无法连接来源或传输中断。请检查网络后重试，或切换公开来源。", "NETWORK", null);
        } catch (Exception e) {
            log.warn("download failed for {}: {}", safeUrl(current == null ? url : current), safeUrlError(e));
            return DownloadResult.fail("下载时无法写入或处理文件。请检查本机存储空间后重试。", "LOCAL_IO", null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String httpFailureCode(int status) {
        if (status == 401 || status == 403) return "HTTP_AUTH_REQUIRED";
        if (status == 404) return "HTTP_NOT_FOUND";
        if (status == 429) return "HTTP_RATE_LIMITED";
        if (status >= 500) return "HTTP_SERVER_ERROR";
        return "HTTP_CLIENT_ERROR";
    }

    private String httpFailureMessage(int status) {
        if (status == 401 || status == 403) return "来源拒绝下载（HTTP " + status + "），可能需要登录、授权或该直链已过期。请换用免登录公开素材或导入已授权本地文件。";
        if (status == 404) return "来源未找到该媒体（HTTP 404），链接可能已失效。请重新搜索或换一个来源。";
        if (status == 429) return "来源暂时限制请求（HTTP 429）。请稍后重试或切换来源。";
        if (status >= 500) return "来源服务暂时异常（HTTP " + status + "）。请稍后重试或切换来源。";
        return "来源拒绝下载（HTTP " + status + "）。请检查链接是否为公开媒体直链。";
    }

    private JsonNode getJson(String url) {
        return getJson(url, 30000);
    }

    /** Bounded public-index lookup used by Studio auto-fill; a blocked source must not stall preparation. */
    private JsonNode getJsonQuick(String url) {
        return getJson(url, 8000);
    }

    private JsonNode getJson(String url, int readTimeoutMs) {
        HttpResult result = httpGet(url, null, readTimeoutMs);
        String s = result.body();
        if (s == null) return null;
        try {
            return om.readTree(s);
        } catch (Exception e) {
            log.warn("JSON response could not be parsed");
            return null;
        }
    }

    /** GET with URL validation before every connection and before every redirect target. */
    /** 直链下载图片/音频（也可用于视频直链）到下载目录，返回本地路径。带 SSRF 校验与重定向跟随。 */
    /** 瞬时失败（连接超时/重置、HTTP 429）的有上限退避重试次数；超过后按原失败处理。 */
    private static final int MAX_TRANSIENT_RETRIES = 3;

    /** 打开连接并读取响应码；对 429 与连接层瞬时失败做有上限退避重试，
     *  避免一时网络波动被立即判为永久失败（下载路径使用）。 */
    private HttpURLConnection openGetWithTransientRetry(String url, int readTimeoutMs) throws Exception {
        int attempts = 0;
        while (true) {
            HttpURLConnection conn = openGet(url, readTimeoutMs);
            int code;
            try {
                code = conn.getResponseCode();
            } catch (Exception e) {
                conn.disconnect();
                if (attempts >= MAX_TRANSIENT_RETRIES) throw e;
                attempts++;
                backoffSleep(attempts);
                continue;
            }
            if (code == 429 && attempts < MAX_TRANSIENT_RETRIES) {
                conn.disconnect();
                attempts++;
                backoffSleep(attempts);
                continue;
            }
            return conn;
        }
    }

    private static void backoffSleep(int attempt) {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Path downloadDirect(String url, String type) throws Exception {
        String current = UrlGuard.validate(url);
        HttpURLConnection conn = null;
        String contentType = "";
        for (int redirects = 0; redirects <= 3; redirects++) {
            conn = openGetWithTransientRetry(current, 180000);
            int code = conn.getResponseCode();
            if (isRedirect(code)) {
                String next = redirectTarget(current, conn.getHeaderField("Location"));
                conn.disconnect();
                conn = null;
                if (next == null) throw new IllegalArgumentException("下载地址重定向失败");
                current = next;
                continue;
            }
            if (code >= 300) {
                conn.disconnect();
                conn = null;
                throw new IllegalArgumentException("下载失败 HTTP " + code);
            }
            contentType = conn.getContentType() == null ? "" : conn.getContentType();
            break;
        }
        if (conn == null) throw new IllegalArgumentException("无法连接下载地址");
        String ext = extFromDownload(type, contentType, current);
        String base;
        try {
            String path = URI.create(current).getPath();
            base = path.substring(path.lastIndexOf('/') + 1).replaceAll("[^a-zA-Z0-9._-]", "_");
        } catch (Exception e) {
            base = "download";
        }
        if (base.isBlank()) base = "download";
        if (base.length() > 60) base = base.substring(base.length() - 60);
        Path dst = props.downloads().resolve(System.currentTimeMillis() + "_" + base + ext);
        try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(dst)) {
            in.transferTo(out);
        } finally {
            conn.disconnect();
        }
        if (!Files.isRegularFile(dst) || Files.size(dst) == 0) {
            Files.deleteIfExists(dst);
            throw new IllegalArgumentException("下载内容为空或不可读");
        }
        return dst;
    }

    private String extFromDownload(String type, String contentType, String url) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if ("image".equalsIgnoreCase(type)) {
            if (ct.contains("png")) return ".png";
            if (ct.contains("webp")) return ".webp";
            if (ct.contains("gif")) return ".gif";
            if (ct.contains("jpeg") || ct.contains("jpg")) return ".jpg";
            return ".jpg";
        }
        if ("audio".equalsIgnoreCase(type)) {
            if (ct.contains("ogg")) return ".ogg";
            if (ct.contains("wav")) return ".wav";
            if (ct.contains("m4a")) return ".m4a";
            if (ct.contains("mpeg") || ct.contains("mp3")) return ".mp3";
            return ".mp3";
        }
        String p = url.toLowerCase();
        int q = p.indexOf('?'); if (q > 0) p = p.substring(0, q);
        if (p.endsWith(".png")) return ".png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return ".jpg";
        if (p.endsWith(".webp")) return ".webp";
        if (p.endsWith(".gif")) return ".gif";
        if (p.endsWith(".ogg")) return ".ogg";
        if (p.endsWith(".wav")) return ".wav";
        if (p.endsWith(".m4a")) return ".m4a";
        if (p.endsWith(".mp3")) return ".mp3";
        if (p.endsWith(".webm")) return ".webm";
        return ".mp4";
    }
    /** GET 结果：code 为 HTTP 状态码，连接失败/重定向异常时为 -1；body 仅在 2xx 时非空。 */
    private record HttpResult(int code, String body) {
    }

    private String getText(String url) {
        return httpGet(url, null).body();
    }

    /**
     * GET with URL validation before every connection and before every redirect target.
     *
     * <p>{@code authHeader} 用于官方 API 的 Authorization 请求头（如 Pexels API Key），
     * 仅第一个请求携带：重定向到其它主机时绝不转发，避免凭据经重定向泄漏；
     * 该值不写入任何日志与返回体。</p>
     */
    private HttpResult httpGet(String url, String authHeader) {
        return httpGet(url, authHeader, 30000);
    }

    private HttpResult httpGet(String url, String authHeader, int readTimeoutMs) {
        HttpURLConnection conn = null;
        String current = null;
        try {
            current = UrlGuard.validate(url);
            for (int redirects = 0; redirects <= 3; redirects++) {
                conn = openGet(current, readTimeoutMs, authHeader);
                int code = conn.getResponseCode();
                if (isRedirect(code)) {
                    String next = redirectTarget(current, conn.getHeaderField("Location"));
                    conn.disconnect();
                    conn = null;
                    if (next == null) return new HttpResult(-1, null);
                    current = next;
                    // Authorization 只在最初请求的官方域名上生效，跨跳转一律丢弃
                    authHeader = null;
                    continue;
                }
                if (code >= 300) {
                    log.warn("GET http {} for {}", code, safeUrl(current));
                    return new HttpResult(code, null);
                }
                try (InputStream in = conn.getInputStream()) {
                    return new HttpResult(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            log.warn("GET rejected after too many redirects for {}", safeUrl(current));
            return new HttpResult(-1, null);
        } catch (Exception e) {
            log.warn("GET failed for {}: {}", safeUrl(current == null ? url : current), safeUrlError(e));
            return new HttpResult(-1, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Opens a validated GET connection pinned to a single pre-resolved address.
     *
     * <p>Callers already ran {@link UrlGuard#validate(String)}. To close the DNS-rebinding / TOCTOU
     * window we resolve the host exactly once via {@link UrlGuard#validateAndResolve(String)} and
     * rewrite the URL host to that concrete IP literal, so the JDK performs no second DNS lookup at
     * connect time. The original Host header is preserved, and for HTTPS the socket factory
     * ({@link PinnedSniSslSocketFactory}) forces SNI and RFC 2818 endpoint identification back to
     * the original hostname so certificate validation still succeeds while the TCP connection stays
     * pinned to the validated address.</p>
     */
    /** Proxy from the local HTTP(S)_PROXY env vars (used by curl/urllib), honoring NO_PROXY. */
    private Proxy systemProxyFor(String scheme, String host) {
        try {
            boolean https = "https".equalsIgnoreCase(scheme);
            String value = https ? firstEnv("HTTPS_PROXY", "https_proxy") : firstEnv("HTTP_PROXY", "http_proxy");
            if (value == null || value.isBlank()) value = firstEnv("HTTP_PROXY", "http_proxy");
            if (value == null || value.isBlank()) return null;
            String noProxy = firstEnv("NO_PROXY", "no_proxy");
            if (noProxy != null && !noProxy.isBlank() && host != null) {
                for (String entry : noProxy.split(",")) {
                    String e = entry.trim();
                    if (!e.isEmpty() && (host.equalsIgnoreCase(e) || host.endsWith("." + e))) return null;
                }
            }
            String spec = value.contains("://") ? value : "http://" + value;
            URI uri = URI.create(spec);
            String ph = uri.getHost();
            if (ph == null || ph.isBlank()) return null;
            int pp = uri.getPort();
            if (pp == -1) pp = https ? 443 : 80;
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ph, pp));
        } catch (Exception e) {
            return null;
        }
    }

    private String firstEnv(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Opens a connection through the machine proxy with the original hostname URL. */
    private HttpURLConnection openViaProxy(String validatedUrl, String scheme, String host,
            int readTimeoutMs, String authHeader, Proxy proxy) throws Exception {
        URL url = new URL(validatedUrl);
        HttpURLConnection conn;
        if ("https".equals(scheme)) {
            HttpsURLConnection https = (HttpsURLConnection) url.openConnection(proxy);
            https.setHostnameVerifier((requested, session) ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session));
            conn = https;
        } else {
            conn = (HttpURLConnection) url.openConnection(proxy);
        }
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Host", host);
        conn.setRequestProperty("Referer", refererOf(validatedUrl));
        if (authHeader != null && !authHeader.isBlank()) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setInstanceFollowRedirects(false);
        conn.connect();
        return conn;
    }

    private HttpURLConnection openGet(String validatedUrl, int readTimeoutMs) throws Exception {
        return openGet(validatedUrl, readTimeoutMs, null);
    }

    private HttpURLConnection openGet(String validatedUrl, int readTimeoutMs, String authHeader) throws Exception {
        URI uri = URI.create(validatedUrl);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        // The JDK does not read HTTP(S)_PROXY env vars. curl/urllib on this machine route through
        // a local proxy (127.0.0.1:7897) and reach these hosts reliably, while direct pinned-IP
        // routes can be unstable. When a proxy is configured and the target is not in NO_PROXY,
        // keep the original hostname URL and connect through the proxy; otherwise fall back to the
        // validated multi-address loop below. UrlGuard still validated the URL before this point.
        Proxy proxy = systemProxyFor(scheme, host);
        if (proxy != null) {
            return openViaProxy(validatedUrl, scheme, host, readTimeoutMs, authHeader, proxy);
        }
        InetAddress[] candidates = UrlGuard.validateAndResolveAll(host);
        if (candidates.length == 0) {
            throw new IllegalArgumentException("URL host cannot be resolved: " + host);
        }
        // Some hosts publish both A and AAAA records; the first record may be unreachable on the
        // current network (e.g. an IPv4 that times out while IPv6 works, or vice versa). Try every
        // validated address in order and keep the first connection that completes the handshake,
        // instead of pinning to candidates[0] only. All candidates pass the same UrlGuard SSRF
        // checks; TLS/SNI/hostname verification still targets the original hostname for each one.
        Exception lastFailure = null;
        int perCandidateConnectMs = candidates.length > 1 ? 10000 : 20000;
        for (InetAddress pinned : candidates) {
            String ipLiteral = pinned.getHostAddress();
            if (pinned instanceof Inet6Address) {
                ipLiteral = "[" + ipLiteral + "]";
            }
            StringBuilder spec = new StringBuilder();
            spec.append(scheme).append("://").append(ipLiteral).append(':').append(port);
            if (uri.getRawPath() != null) {
                spec.append(uri.getRawPath());
            }
            if (uri.getRawQuery() != null) {
                spec.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                spec.append('#').append(uri.getRawFragment());
            }
            HttpURLConnection conn = null;
            try {
                if ("https".equals(scheme)) {
                    HttpsURLConnection https = (HttpsURLConnection) new URL(spec.toString()).openConnection();
                    https.setSSLSocketFactory(new PinnedSniSslSocketFactory(host, pinned,
                            (SSLSocketFactory) SSLSocketFactory.getDefault()));
                    // The socket factory enables endpoint identification on the TLS socket itself, so the
                    // handshake validates the certificate chain AND the original hostname. This verifier is
                    // retained as a second line of defense for JDK paths where the socket-level check is not
                    // applied: it must still match the original hostname, never the pinned IP literal.
                    https.setHostnameVerifier((requested, session) ->
                            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session));
                    conn = https;
                } else {
                    conn = (HttpURLConnection) new URL(spec.toString()).openConnection();
                }
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Host", host);
                conn.setRequestProperty("Referer", refererOf(validatedUrl));
                if (authHeader != null && !authHeader.isBlank()) {
                    // 官方 API Key 走请求头（如 Pexels：Authorization: <API_KEY>）；调用方保证
                    // 该值只来自服务端环境变量，且不进入日志、异常信息与返回体。
                    conn.setRequestProperty("Authorization", authHeader);
                }
                conn.setConnectTimeout(perCandidateConnectMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.setInstanceFollowRedirects(false);
                // Force TCP + TLS handshake now so an unreachable candidate can fall through to
                // the next address; httpGet() later calls getResponseCode() on the same connection.
                conn.connect();
                return conn;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("connect attempt to {} via {} failed: {}", host, ipLiteral, safeUrlError(e));
                if (conn != null) { try { conn.disconnect(); } catch (Exception ignore) { } }
            }
        }
        throw lastFailure != null ? lastFailure
                : new IllegalArgumentException("无法连接到 " + host);
    }

    /**
     * Wraps the default {@link SSLSocketFactory} so a TLS connection to a pinned IP literal still
     * authenticates the original hostname.
     *
     * <p>{@link HttpsURLConnection} derives both SNI and the hostname-check target from the URL
     * host, which {@link #openGet(String, int)} rewrote to the pinned IP literal. RFC 6066 forbids
     * SNI entries that are IP literals, so the JDK serves/validates the endpoint's default
     * certificate instead and hostname verification fails against the literal. This factory fixes
     * that in two steps, neither of which weakens certificate checks:</p>
     *
     * <ul>
     *   <li>Every created socket receives SSLParameters with
     *   {@code setEndpointIdentificationAlgorithm("HTTPS")} and, for hostnames, an explicit
     *   {@code SNIHostName}. Endpoint identification then runs inside the TLS handshake against the
     *   original hostname (RFC 2818), enforced by the default {@code X509ExtendedTrustManager} with
     *   the default trust store — it cannot be bypassed by the application-level verifier.</li>
     *   <li>The standalone {@code createSocket(String, ...)} variants connect to the pinned address
     *   captured at construction instead of re-resolving the hostname, so no call path re-opens the
     *   DNS-rebinding window that {@link UrlGuard#validateAndResolve(String)} closed.</li>
     * </ul>
     */
    static final class PinnedSniSslSocketFactory extends SSLSocketFactory {
        private final String sniHost;
        private final InetAddress pinned;
        private final SSLSocketFactory delegate;

        PinnedSniSslSocketFactory(String sniHost, InetAddress pinned, SSLSocketFactory delegate) {
            this.sniHost = sniHost;
            this.pinned = pinned;
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            // HttpsURLConnection passes the pinned IP literal here; substitute the original hostname
            // so the server certificate matches and SNI/endpoint identification target it.
            return configure(delegate.createSocket(s, sniHost, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket raw = connectPinned(port);
            return configure(delegate.createSocket(raw, sniHost, port, true));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket raw = connectPinned(port, localHost, localPort);
            return configure(delegate.createSocket(raw, sniHost, port, true));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return configure(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
            return configure(delegate.createSocket(host, port, localHost, localPort));
        }

        /** TCP connect straight to the UrlGuard-pinned address; never re-resolve the hostname. */
        private Socket connectPinned(int port) throws IOException {
            Socket raw = new Socket();
            try {
                raw.connect(new java.net.InetSocketAddress(pinned, port), 20000);
                return raw;
            } catch (IOException | RuntimeException e) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // original failure is more useful
                }
                throw e;
            }
        }

        private Socket connectPinned(int port, InetAddress localHost, int localPort) throws IOException {
            Socket raw = new Socket();
            try {
                raw.bind(new java.net.InetSocketAddress(localHost, localPort));
                raw.connect(new java.net.InetSocketAddress(pinned, port), 20000);
                return raw;
            } catch (IOException | RuntimeException e) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // original failure is more useful
                }
                throw e;
            }
        }

        /**
         * Forces the TLS handshake to verify the peer's identity against the original hostname.
         * The peer certificate chain is still validated by the default trust manager; setting
         * "HTTPS" endpoint identification only adds the RFC 2818 hostname check to it.
         */
        private Socket configure(Socket socket) {
            if (socket instanceof SSLSocket ssl) {
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isIpLiteral(sniHost)) {
                    params.setServerNames(List.of(new SNIHostName(sniHost)));
                }
                ssl.setSSLParameters(params);
            }
            return socket;
        }

        /** RFC 6066 forbids SNI entries that are IP literals; detect them before constructing one. */
        private static boolean isIpLiteral(String host) {
            if (host == null || host.isEmpty()) return false;
            if (host.indexOf(':') >= 0) return true; // IPv6 (brackets already stripped by URI)
            return host.matches("[0-9.]+");
        }
    }

    private String redirectTarget(String currentUrl, String location) {
        if (location == null || location.isBlank()) {
            log.warn("redirect without Location for {}", safeUrl(currentUrl));
            return null;
        }
        try {
            String resolved = URI.create(currentUrl).resolve(location.trim()).toString();
            return UrlGuard.validate(resolved);
        } catch (IllegalArgumentException e) {
            log.warn("redirect target rejected for {}: {}", safeUrl(currentUrl), safeUrlError(e));
            return null;
        }
    }

    private boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307 || code == 308;
    }

    // ==================== 工具 ====================

    private Path newestWithPrefix(Path dir, String prefix) {
        return newestDownloadedMedia(dir, Set.of(), 0, prefix);
    }

    private Set<Path> snapshotFiles(Path dir) {
        try (var st = Files.walk(dir, 4)) {
            return st.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            return Set.of();
        }
    }

    private Path newestDownloadedMedia(Path dir, Set<Path> before, long startedAt, String prefix) {
        try (var st = Files.walk(dir, 4)) {
            return st.filter(Files::isRegularFile)
                    .filter(p -> !before.contains(p))
                    .filter(p -> prefix == null || prefix.isBlank() || p.getFileName().toString().startsWith(prefix) || p.toFile().lastModified() >= startedAt)
                    .filter(p -> p.toFile().length() > 4096)
                    .filter(this::isMediaFile)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isMediaFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.matches(".*\\.(mp4|webm|mkv|mov|avi|m4v|mp3|wav|m4a|aac|ogg|opus|flac|jpg|jpeg|png|webp)$");
    }

    private boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|avif)$");
    }

    private List<Path> newImages(Set<Path> before, Path dir) {
        try (var st = Files.walk(dir, 4)) {
            return st.filter(Files::isRegularFile)
                    .filter(p -> !before.contains(p))
                    .filter(p -> p.toFile().length() > 1024)
                    .filter(this::isImageFile)
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean likelyPublicMediaEndpoint(String url) {
        return url != null && !url.contains(" ") && !url.toLowerCase(Locale.ROOT).contains("/watch") && !url.toLowerCase(Locale.ROOT).contains("/login");
    }

    private boolean looksLikeDirectMedia(String url) {
        String u = url.toLowerCase();
        return u.contains(".mp4") || u.contains(".mov") || u.contains(".m4v")
                || u.contains(".mp3") || u.contains(".wav") || u.contains(".m4a")
                || u.contains(".jpg") || u.contains(".png") || u.contains(".webp");
    }

    private String refererOf(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost() + "/";
        } catch (Exception e) {
            return "";
        }
    }

    private String fileNameOf(String url) {
        String u = url.split("[?#]")[0];
        int i = u.lastIndexOf('/');
        return i >= 0 ? u.substring(i + 1) : u;
    }

    private String extOf(String url) {
        String n = fileNameOf(url);
        int i = n.lastIndexOf('.');
        return (i > 0 && n.length() - i <= 6) ? n.substring(i) : "";
    }

    private String prettyName(String f) {
        int i = f.lastIndexOf('.');
        String n = i > 0 ? f.substring(0, i) : f;
        return n.replace('-', ' ').replace('_', ' ');
    }

    /**
     * 文件名清洗。
     *
     * 除了 Windows 非法字符，还要挡掉 ffmpeg 敏感字符：
     *   #  在 filter_complex / concat 清单里是注释符，会把后面的内容整行吃掉
     *   &amp;  在部分 shell 传参场景会截断命令
     *   , : ' [ ] 是 ffmpeg filter 的分隔符/转义符
     * 抓下来的素材名不受我们控制（站点标签里什么都有），这里必须从严。
     */
    private String safeName(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_")   // Windows 非法字符 + 空白
                    .replaceAll("[#&,;'\\[\\]()`$!%^{}=+·]+", "_") // ffmpeg / shell 敏感字符
                    .replaceAll("_+", "_")
                    .replaceAll("^[_.]+|[_.]+$", "");
        return t.isBlank() ? "material" : t;
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /**
     * Percent-encodes a single URL path segment (RFC 3986 unreserved set only), unlike
     * {@link #enc(String)} which is {@code application/x-www-form-urlencoded} (space -&gt; '+')
     * and only valid inside a query string. MediaWiki and archive.org do not decode '+' inside
     * path segments, so titles/identifiers containing spaces used to produce broken page and
     * download URLs (e.g. {@code /wiki/File:Cats+and+dogs.jpg} pointing at a non-existent page).
     * Package-private for focused unit tests; keeps the same redaction/encoding guarantees as enc.
     */
    static String encPath(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() * 3);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xff;
            if ((v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z') || (v >= '0' && v <= '9')
                    || v == '-' || v == '.' || v == '_' || v == '~') {
                sb.append((char) v);
            } else {
                sb.append('%').append(HEX[v >>> 4]).append(HEX[v & 0xf]);
            }
        }
        return sb.toString();
    }

    private String describeToolFailure(String tool, ProcRunner.Result result) {
        String detail = tailOf(result == null ? null : result.out()).toLowerCase(Locale.ROOT);
        String prefix = tool + " 执行失败（退出码 " + (result == null ? "未知" : result.code()) + "）：";
        if (detail.contains("drm")) return prefix + "该页面受 DRM 保护，不能作为公开素材下载。请换用获得授权的无 DRM 素材。";
        if (detail.contains("sign in") || detail.contains("login") || detail.contains("cookies")) return prefix + "该页面需要登录。请在官网完成授权后导入已下载的本地文件。";
        if (detail.contains("geo") || detail.contains("country") || detail.contains("region")) return prefix + "该内容受地区限制。请切换公开来源或导入已授权本地文件。";
        if (detail.contains("404") || detail.contains("not found")) return prefix + "页面或媒体已不存在。请重新选择来源链接。";
        if (detail.contains("403") || detail.contains("forbidden")) return prefix + "来源拒绝访问，链接可能已过期或不允许公开下载。请换用公开媒体直链。";
        if (detail.contains("timeout") || detail.contains("timed out")) return prefix + "来源响应超时。请稍后重试或切换来源。";
        return prefix + "请确认链接公开、免登录、未受 DRM 或地区限制，并尝试来源提供的直接媒体地址。";
    }

    private String tailOf(String s) {
        if (s == null) return "";
        return redact(s.length() <= 800 ? s : s.substring(s.length() - 800));
    }

    /** Logs only the URL origin and path; query strings may contain official API credentials. */
    private String safeUrl(String value) {
        if (value == null || value.isBlank()) return "[empty URL]";
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) return "[invalid URL]";
            String authority = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            return uri.getScheme() + "://" + authority + (uri.getPath() == null ? "" : uri.getPath());
        } catch (IllegalArgumentException e) {
            return "[invalid URL]";
        }
    }

    private String safeUrlError(Exception e) {
        return redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    /**
     * Redacted, human-readable description of an exception, safe to surface in API responses
     * (query strings may contain official API credentials). Public so dependent services
     * (e.g. MaterialGapService) can return actionable failures without leaking secrets.
     */
    public static String safeError(Exception e) {
        return redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private static String redact(String value) {
        if (value == null) return "";
        // Authorization 请求头形式（官方 API Key 走这里）：整段抹掉，含冒号/等号后的值
        return value
                .replaceAll("(?i)authorization\\s*[:=]\\s*[^\\s,;]+", "Authorization=***")
                .replaceAll("(?i)(key|token|api[_-]?key|authorization)=?[^\\s&]+", "$1=***")
                .replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer ***");
    }
}
