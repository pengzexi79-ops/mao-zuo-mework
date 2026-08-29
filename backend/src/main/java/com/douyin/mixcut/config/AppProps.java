package com.douyin.mixcut.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** application.yml 里 app.* 的强类型映射。 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProps {

    /** Release identity is compiled into the application and must never be environment-overridable. */
    private static final String RELEASE_VERSION = "2.2.157";

    private String dataDir = "./data";
    private String ffmpeg = "ffmpeg";
    private String ffprobe = "ffprobe";
    private String materialsDir = "./data/materials";
    private String outputDir = "./data/output";
    /** User-approved root for subsequent media-tool and AI-generation files. Empty stays application-managed. */
    private String mediaToolsOutputDir = "";
    private String cacheDir = "./data/cache";
    /** Project-private Python runtime for local ASR, OCR, neural TTS, and loudness normalization. */
    private String localPython = "./backend/.venv/Scripts/python.exe";

    /** Path to the media_diagnose.py script. Resolved relative to project root; falls back to tools/media_diagnose.py. */
    private String mediaDiagnoseScript = "";

    public String releaseVersion() {
        return RELEASE_VERSION;
    }

    /** Resolve the configured Python executable against the install/project root when it is relative. */
    public String localPythonPath() {
        if (localPython == null || localPython.isBlank()) return "python";
        Path configured = Paths.get(localPython);
        if (configured.isAbsolute()) return configured.normalize().toString();
        Path root = projectRoot();
        Path resolved = root.resolve(configured).normalize();
        if (Files.isRegularFile(resolved)) return resolved.toString();
        return root.resolve(localPython.replaceFirst("^\\./", "")).normalize().toString();
    }

    private String ytdlpPath = "yt-dlp";
    private String yougetPath = "you-get";
    private String gallerydlPath = "gallery-dl";

    private String freesoundApiKey = "";
    private String pixabayApiKey = "";
    /** Pexels 视频检索 API Key，仅从服务端环境变量 APP_PEXELS_API_KEY 读取，绝不写入前端或日志。 */
    private String pexelsApiKey = "";
    /** Unsplash 图片检索 Access Key，仅从服务端环境变量 APP_UNSPLASH_API_KEY 读取。 */
    private String unsplashApiKey = "";

    /** AES-GCM 主密钥仅允许由 APP_MASTER_KEY 或外部密钥服务提供，绝不写入项目文件。 */
    private String masterKey = "";
    /** 显式本地 AI Provider 可用的回环端口白名单。 */
    private String localAiAllowedPorts = "11434";

    /** Shared outbound network limits; individual callers may only tighten these values. */
    private int networkConnectTimeoutSec = 20;
    private int networkReadTimeoutSec = 30;
    private int networkTotalTimeoutSec = 120;
    private int networkMaxRedirects = 3;
    private int networkMaxRetries = 2;
    private int networkRetryBackoffMs = 400;
    private long networkMaxResponseBytes = 2_000_000L;
    private long networkMaxDownloadBytes = 200_000_000L;

    /** Optional shared token. An empty value keeps local development login-free. */
    private String accessToken = "";

    /** Browser origins allowed to call the API when credentials are sent. */
    private List<String> corsAllowedOrigins = new ArrayList<>(List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173"
    ));

    /** Bind locally by default; set explicitly before exposing the service. */
    private String bindAddress = "127.0.0.1";

    /** 合规开关：需登录才能访问的平台，默认禁止抓取 */
    private boolean allowLoginCrawl = false;

    /** 渲染线程池与任务 watchdog 配置。 */
    /** 同时编码的本地任务数；高分辨率视频建议按 CPU/内存逐步提高。 */
    private int renderPoolSize = 2;
    /** 安全上限，前端不能提交超过该值的本地并发请求。 */
    private int renderMaxConcurrency = 8;
    private int renderQueueCapacity = 200;
    private int jobTimeoutSec = 7200;
    private int jobStaleAfterSec = 900;
    private int jobWatchdogDelayMs = 30000;

    /** 成片交付质检：默认拒绝无声、长静音或明显音画漂移的输出。 */
    private boolean qcAllowSilentAudio = false;
    private double qcMaxSilenceSec = 3.0;
    private double qcMaxAvDriftSec = 0.5;
    private double qcMaxBlackRatio = 0.02;
    /** 成片中异常纯红/品红（错误占位帧）累计时长占成片比例上限，超过即拦截。 */
    private double qcMaxRedMagentaRatio = 0.02;
    /** 单条成片自动修复的最大迭代次数；无改善或达到上限即转人工决策。 */
    private int maxRepairIterations = 3;

    public Path data() {
        return ensure(Paths.get(dataDir));
    }

    public Path materials() {
        return ensure(managedPath(materialsDir, "materials"));
    }

    public Path output() {
        return ensure(managedPath(outputDir, "output"));
    }

    public Path cache() {
        return ensure(managedPath(cacheDir, "cache"));
    }

    /** Dedicated root for later media-tool and AI-generated assets; old files are never moved. */
    public Path mediaToolsOutput() {
        if (mediaToolsOutputDir == null || mediaToolsOutputDir.isBlank()) return ensure(materials().resolve("media-tools"));
        return ensure(Path.of(mediaToolsOutputDir));
    }

    /** 素材下载落地目录 */
    public Path downloads() {
        return ensure(materials().resolve("_downloads"));
    }

    /** 中间切片目录 */
    public Path slices() {
        return ensure(cache().resolve("slices"));
    }

    public Path thumbs() {
        return ensure(cache().resolve("thumbs"));
    }

    /** Resolve the media_diagnose.py script path independent of user.dir.
     *  Checks the configured path first, then falls back to project-root-relative tools/media_diagnose.py. */
    public Path mediaDiagnoseScriptPath() {
        if (mediaDiagnoseScript != null && !mediaDiagnoseScript.isBlank()) {
            Path configured = Paths.get(mediaDiagnoseScript).toAbsolutePath().normalize();
            if (Files.exists(configured)) return configured;
        }
        // Fallback: resolve relative to the project root (parent of data dir, or working dir)
        Path projectRoot = projectRoot();
        Path rootTool = projectRoot.resolve("tools").resolve("media_diagnose.py");
        if (Files.isRegularFile(rootTool)) return rootTool;
        return projectRoot.resolve("backend").resolve("tools").resolve("media_diagnose.py");
    }

    /** Absolute path of the bundled Python venv Scripts dir (backend/.venv/Scripts). */
    public Path venvScriptsDir() {
        Path projectRoot = projectRoot();
        Path scripts = projectRoot.resolve("backend").resolve(".venv").resolve("Scripts");
        return Files.isDirectory(scripts) ? scripts : null;
    }

    /** Resolve a binary inside the bundled portable runtime dir (portable/<subPath>).
     *  Returns the absolute path when present, otherwise null (caller falls back to PATH). */
    public String portableTool(String subPath) {
        Path projectRoot = projectRoot();
        Path tool = projectRoot.resolve("portable").resolve(subPath);
        return Files.isRegularFile(tool) ? tool.toString() : null;
    }
    /** Resolve a tool shipped inside the bundled venv (you-get / auto-editor / rembg / demucs / whisper-cli).
     *  Returns the absolute exe path when present; otherwise falls back to the plain command name (PATH lookup). */
    public String venvTool(String tool) {
        Path scripts = venvScriptsDir();
        if (scripts != null) {
            Path exe = scripts.resolve(tool + ".exe");
            if (Files.isRegularFile(exe)) return exe.toString();
        }
        return tool;
    }
    /** 项目根定位：优先当前工作目录（安装版/开发版都以项目根为 workdir），回退 data 父目录。 */
    private Path projectRoot() {
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            Path candidate = Paths.get(userDir).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate.resolve("backend"))) return candidate;
        }
        Path parent = data().getParent();
        return parent != null ? parent : Paths.get("").toAbsolutePath().normalize();
    }
    private Path managedPath(String configured, String defaultChild) {
        String value = configured == null || configured.isBlank() ? defaultChild : configured;
        Path path = Paths.get(value);
        if (path.isAbsolute()) return path.normalize();
        Path dataPath = Paths.get(dataDir).toAbsolutePath().normalize();
        String normalized = value.replace('\\', '/').replaceFirst("^\\./", "");
        if (normalized.equals("data") || normalized.equals("./data")) return dataPath;
        if (normalized.startsWith("data/")) return dataPath.resolve(normalized.substring("data/".length())).normalize();
        return dataPath.resolve(normalized).normalize();
    }

    private Path ensure(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        File f = resolved.toFile();
        if (!f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
        }
        return resolved;
    }

    private Path ensure(String p) {
        Path path = Paths.get(p).toAbsolutePath().normalize();
        File f = path.toFile();
        if (!f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
        }
        return path;
    }
}
