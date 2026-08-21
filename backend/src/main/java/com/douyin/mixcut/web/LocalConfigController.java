package com.douyin.mixcut.web;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.service.LocalReleaseHistoryService;
import com.douyin.mixcut.service.CredentialRegistry;
import com.douyin.mixcut.external.CrawlerGateway;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Local-only setup endpoint. It tests and writes the project .env without ever returning a password.
 * The app is intended to bind to loopback; this controller rejects non-loopback callers as a second boundary.
 */
@RestController
@RequestMapping("/api/local-config")
@RequiredArgsConstructor
public class LocalConfigController {

    private static final Pattern DATABASE = Pattern.compile("^[A-Za-z0-9_$-]{1,64}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.$@-]{1,64}$");
    private final AppProps props;
    private final LocalReleaseHistoryService localReleaseHistory;
    private final CrawlerGateway crawler;
    private final CredentialRegistry credentialRegistry;

    @Data
    public static class MysqlRequest {
        private String host = "127.0.0.1";
        private Integer port = 3306;
        private String database;
        private String username;
        private String password;
        private Boolean confirm = false;
    }

    /** One user-owned official provider key. The value is never returned after this request. */
    @Data
    public static class SourceKeyRequest {
        /** Stable manifest config ID. provider remains accepted for existing local clients. */
        private String configId;
        private String provider;
        private String apiKey;
        private Boolean confirm = false;
    }

    @Data
    public static class OutputLocationRequest {
        /** default = application data/output, desktop = the current user's Desktop, custom = explicit local path. */
        private String mode = "default";
        private String path;
        private Boolean confirm = false;
    }

    @GetMapping("/release/status")
    public R<Map<String, Object>> releaseStatus(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        try {
            return R.ok(localReleaseHistory.status());
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PutMapping("/release/pending")
    public R<Map<String, Object>> saveReleasePending(@RequestBody Map<String, Object> draft, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        try {
            return R.ok(localReleaseHistory.savePending(draft));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/release/check")
    public R<Map<String, Object>> checkReleasePending(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        try {
            return R.ok(localReleaseHistory.checkPending());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/release/apply")
    public R<Map<String, Object>> applyReleasePending(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        try {
            return R.ok(localReleaseHistory.apply(localReleaseHistory.pending()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/release/sync")
    public R<Map<String, Object>> syncReleaseHistory(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        try {
            return R.ok(localReleaseHistory.syncBundledHistory());
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/mysql/test")
    public R<Map<String, Object>> testMysql(@RequestBody MysqlRequest request, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        try {
            MysqlSettings settings = validate(request);
            try (Connection connection = DriverManager.getConnection(settings.url(), settings.username(), settings.password());
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("SELECT 1");
            }
            return R.ok(Map.of("connected", true, "message", "连接测试成功。确认后可保存到项目本机 .env，并重启应用生效。"));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (SQLException e) {
            return R.ok(testFailure(e));
        }
    }

    @PostMapping("/mysql/save")
    public R<Map<String, Object>> saveMysql(@RequestBody MysqlRequest request, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        if (!Boolean.TRUE.equals(request.getConfirm())) return R.fail("请确认后再保存本机数据库配置");
        try {
            MysqlSettings settings = validate(request);
            try (Connection connection = DriverManager.getConnection(settings.url(), settings.username(), settings.password());
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("SELECT 1");
            }
            Path env = projectRoot().resolve(".env");
            boolean backedUp = Files.exists(env);
            backup(env);
            writeEnv(env, Map.of(
                    "DB_URL", settings.url(),
                    "DB_USERNAME", settings.username(),
                    "DB_PASSWORD", settings.password()));
            return R.ok(Map.of(
                    "saved", true,
                    "restartRequired", true,
                    "backedUp", backedUp,
                    "writtenVariables", List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD"),
                    "message", "数据库配置已安全保存。下一步请点击“应用配置并重启后端”，页面会自动重新检测。"));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (SQLException e) {
            return R.ok(testFailure(e));
        } catch (IOException e) {
            return R.fail("无法写入本机 .env：请检查项目目录写入权限");
        }
    }

    @GetMapping("/source-keys")
    public R<Map<String, Object>> sourceKeyStatus(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        Map<String, Object> statuses = new LinkedHashMap<>();
        for (CredentialRegistry.Credential credential : credentialRegistry.all()) {
            statuses.put(credential.configId(), credentialRegistry.status(credential, props));
        }
        return R.ok(statuses);
    }

    @PostMapping("/source-keys/save")
    public R<Map<String, Object>> saveSourceKey(@RequestBody SourceKeyRequest request, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        if (!Boolean.TRUE.equals(request.getConfirm())) return R.fail("请确认后再保存本机来源 API Key");
        try {
            CredentialRegistry.Credential credential = validateSourceCredential(request, true);
            Path env = projectRoot().resolve(".env");
            boolean backedUp = Files.exists(env);
            backup(env);
            writeEnv(env, Map.of(credential.environmentVariable(), request.getApiKey().trim()));
            Map<String, Object> result = new LinkedHashMap<>(credentialRegistry.metadata(credential));
            result.put("saved", true);
            result.put("restartRequired", credential.restartRequired());
            result.put("backedUp", backedUp);
            result.put("writtenVariables", List.of(credential.environmentVariable()));
            result.put("message", "来源密钥已保存到本机配置。请应用配置并重启后端，再测试连接。");
            return R.ok(result);
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("无法写入本机 .env：请检查项目目录写入权限");
        }
    }

    @PostMapping("/source-keys/test")
    public R<Map<String, Object>> testSourceKey(@RequestBody SourceKeyRequest request, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        try {
            CredentialRegistry.Credential credential = validateSourceCredential(request, false);
            if (!credentialRegistry.configured(credential, props)) return R.ok(Map.of(
                    "connected", false, "category", "restart_required",
                    "message", "当前进程尚未加载该来源密钥。请先保存并重启后端，再测试连接。"));
            List<CrawlerGateway.RemoteItem> results = "audio".equals(credential.mediaType())
                    ? crawler.searchAudio(credential.provider(), credential.testQuery(), 1)
                    : crawler.searchVideo(credential.provider(), credential.testQuery(), 1);
            CrawlerGateway.RemoteItem notice = results.stream().filter(CrawlerGateway.RemoteItem::isNotice).findFirst().orElse(null);
            if (notice != null) {
                String title = notice.getTitle() == null ? "" : notice.getTitle();
                String category = title.contains("限流") || title.contains("429") ? "rate_limited" : "provider_rejected";
                return R.ok(Map.of(
                        "connected", false, "category", category,
                        "message", safeProviderMessage(notice.getTitle())));
            }
            return R.ok(Map.of(
                    "connected", true, "category", "ready",
                    "resultCount", results.size(), "message", "官方来源连接测试成功，可参与受限素材检索。"));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            return R.ok(Map.of("connected", false, "category", "network_or_provider_error",
                    "message", "无法完成来源测试，请检查网络、配额或来源服务状态后重试。"));
        }
    }

    @PostMapping("/restart")
    public R<Map<String, Object>> restart(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        try {
            Path root = projectRoot().toRealPath();
            Path launcher = root.resolve("start.bat").normalize();
            if (!launcher.startsWith(root) || Files.isSymbolicLink(launcher) || !Files.isRegularFile(launcher)) {
                return R.fail("项目启动脚本校验失败，拒绝自动重启");
            }
            Path verifiedLauncher = launcher.toRealPath();
            if (!verifiedLauncher.startsWith(root) || !verifiedLauncher.getFileName().toString().equalsIgnoreCase("start.bat")) {
                return R.fail("项目启动脚本不在固定项目目录内，拒绝自动重启");
            }
            long currentPid = ProcessHandle.current().pid();
            Path script = props.cache().resolve("restart-mework-" + currentPid + ".cmd");
            String escapedLauncher = verifiedLauncher.toString().replace("\"", "\"\"");
            List<String> lines = List.of(
                    "@echo off",
                    "timeout /t 2 /nobreak >nul",
                    "taskkill /PID " + currentPid + " /F >nul 2>&1",
                    "for /l %%i in (1,1,15) do (",
                    "  netstat -ano -p TCP | findstr /R /C:\":8760 .*LISTENING\" >nul || goto restart_ready",
                    "  timeout /t 1 /nobreak >nul",
                    ")",
                    ":restart_ready",
                    "call \"" + escapedLauncher + "\"",
                    "del \"%~f0\" >nul 2>&1");
            Files.write(script, lines, StandardCharsets.UTF_8);
            new ProcessBuilder("cmd.exe", "/d", "/c", "call", script.toString())
                    .directory(root.toFile()).start();
            return R.ok(Map.of("restarting", true, "message", "正在应用本机配置并重启后端。页面会等待旧进程释放端口后再启动，并重新检测环境。"));
        } catch (IOException e) {
            return R.fail("无法创建本机重启任务：请检查项目目录写入权限");
        }
    }

    @GetMapping("/output-location")
    public R<Map<String, Object>> outputLocation(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        return R.ok(Map.of("path", props.output().toString(), "restartRequired", false,
                "message", "该位置只影响后续成片；既有成片不会被移动或删除。"));
    }

    @PostMapping("/output-location")
    public R<Map<String, Object>> saveOutputLocation(@RequestBody OutputLocationRequest request, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        if (request == null || !Boolean.TRUE.equals(request.getConfirm())) return R.fail("请确认后再修改后续成片保存位置");
        try {
            Path target = validateOutputLocation(request);
            Path env = projectRoot().resolve(".env");
            boolean backedUp = Files.exists(env);
            backup(env);
            writeEnv(env, Map.of("APP_OUTPUT_DIR", target.toString()));
            props.setOutputDir(target.toString());
            return R.ok(Map.of("path", props.output().toString(), "saved", true, "backedUp", backedUp,
                    "restartRequired", false, "message", "后续成片将保存到此位置；既有成片保持原位置。"));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("无法创建或写入目标目录，请检查本机文件权限");
        }
    }

    @GetMapping("/media-tools-output-location")
    public R<Map<String, Object>> mediaToolsOutputLocation(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        return R.ok(Map.of("path", props.mediaToolsOutput().toString(), "mode", "custom",
                "restartRequired", false, "message", "该位置只影响后续媒体工具和 AI 生成素材；既有文件不会被移动或删除。"));
    }

    @PostMapping("/media-tools-output-location")
    public R<Map<String, Object>> saveMediaToolsOutputLocation(@RequestBody OutputLocationRequest request, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        if (request == null || !Boolean.TRUE.equals(request.getConfirm())) return R.fail("请确认后再修改后续媒体工具保存位置");
        try {
            Path target = validateMediaToolsOutputLocation(request);
            Path env = projectRoot().resolve(".env");
            boolean backedUp = Files.exists(env);
            backup(env);
            writeEnv(env, Map.of("APP_MEDIA_TOOLS_OUTPUT_DIR", target.toString()));
            props.setMediaToolsOutputDir(target.toString());
            return R.ok(Map.of("path", props.mediaToolsOutput().toString(), "saved", true, "backedUp", backedUp,
                    "restartRequired", false, "message", "后续媒体工具和 AI 生成素材将保存到此位置；既有文件保持原位置。"));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("无法创建或写入目标目录，请检查本机文件权限");
        }
    }

    @GetMapping("/setup-status")
    public R<Map<String, Object>> setupStatus(HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) return R.fail("本机配置接口仅允许从 127.0.0.1 访问");
        Path env = projectRoot().resolve(".env");
        boolean hasDbSettings = false;
        try {
            if (Files.exists(env)) {
                List<String> keys = Files.readAllLines(env, StandardCharsets.UTF_8);
                hasDbSettings = keys.stream().anyMatch(line -> line.startsWith("DB_URL="))
                        && keys.stream().anyMatch(line -> line.startsWith("DB_USERNAME="))
                        && keys.stream().anyMatch(line -> line.startsWith("DB_PASSWORD="));
            }
        } catch (IOException ignored) { }
        return R.ok(Map.of("envPresent", Files.exists(env), "databaseSettingsPresent", hasDbSettings));
    }

    private Path validateOutputLocation(OutputLocationRequest request) throws IOException {
        String mode = request.getMode() == null ? "default" : request.getMode().trim().toLowerCase();
        Path target;
        if ("default".equals(mode)) {
            target = projectRoot().resolve("data").resolve("output");
        } else if ("desktop".equals(mode)) {
            target = Path.of(System.getProperty("user.home"), "Desktop", "Mework Outputs");
        } else if ("custom".equals(mode)) {
            if (request.getPath() == null || request.getPath().isBlank()) throw new IllegalArgumentException("请选择本机成片保存目录");
            target = Path.of(request.getPath());
            if (!target.isAbsolute()) throw new IllegalArgumentException("自定义保存位置必须是本机绝对路径");
        } else {
            throw new IllegalArgumentException("不支持的成片保存位置");
        }
        target = target.toAbsolutePath().normalize();
        Path root = target.getRoot();
        if (root == null || target.equals(root)) throw new IllegalArgumentException("不能将磁盘根目录设为成片保存位置");
        Path project = projectRoot().toAbsolutePath().normalize();
        if (target.equals(project) || target.equals(project.resolve("backend"))) throw new IllegalArgumentException("不能将项目程序目录设为成片保存位置");
        String lower = target.toString().toLowerCase();
        if (lower.matches("^[a-z]:\\\\(windows|program files|program files \\(x86\\))($|\\\\.*)")) {
            throw new IllegalArgumentException("不能将系统目录设为成片保存位置");
        }
        Files.createDirectories(target);
        if (!Files.isDirectory(target) || !Files.isWritable(target)) throw new IllegalArgumentException("目标目录不可写");
        return target;
    }

    private Path validateMediaToolsOutputLocation(OutputLocationRequest request) throws IOException {
        String mode = request.getMode() == null ? "default" : request.getMode().trim().toLowerCase();
        if ("default".equals(mode)) return validateWritableOutputPath(projectRoot().resolve("data").resolve("materials").resolve("media-tools"), "媒体工具保存位置");
        if ("desktop".equals(mode)) return validateWritableOutputPath(Path.of(System.getProperty("user.home"), "Desktop", "Mework Media"), "媒体工具保存位置");
        if (!"custom".equals(mode)) throw new IllegalArgumentException("不支持的媒体工具保存位置");
        if (request.getPath() == null || request.getPath().isBlank()) throw new IllegalArgumentException("请选择本机媒体工具保存目录");
        Path target = Path.of(request.getPath());
        if (!target.isAbsolute()) throw new IllegalArgumentException("自定义保存位置必须是本机绝对路径");
        return validateWritableOutputPath(target, "媒体工具保存位置");
    }

    private Path validateWritableOutputPath(Path rawTarget, String label) throws IOException {
        Path target = rawTarget.toAbsolutePath().normalize();
        Path root = target.getRoot();
        if (root == null || target.equals(root)) throw new IllegalArgumentException("不能将磁盘根目录设为" + label);
        Path project = projectRoot().toAbsolutePath().normalize();
        if (target.equals(project) || target.equals(project.resolve("backend"))) throw new IllegalArgumentException("不能将项目程序目录设为" + label);
        String lower = target.toString().toLowerCase();
        if (lower.matches("^[a-z]:\\\\(windows|program files|program files \\(x86\\))($|\\\\.*)")) throw new IllegalArgumentException("不能将系统目录设为" + label);
        Files.createDirectories(target);
        if (!Files.isDirectory(target) || !Files.isWritable(target)) throw new IllegalArgumentException("目标目录不可写");
        return target;
    }

    private CredentialRegistry.Credential validateSourceCredential(SourceKeyRequest request, boolean requireValue) {
        if (request == null) throw new IllegalArgumentException("缺少来源密钥配置");
        String configId = request.getConfigId() == null ? "" : request.getConfigId().trim();
        String provider = request.getProvider() == null ? "" : request.getProvider().trim();
        CredentialRegistry.Credential credential = (!configId.isBlank()
                ? credentialRegistry.byConfigId(configId)
                : credentialRegistry.byProvider(provider))
                .orElseThrow(() -> new IllegalArgumentException("不支持的来源密钥类型"));
        if (requireValue) credentialRegistry.validateValue(credential, request.getApiKey());
        return credential;
    }

    private String safeProviderMessage(String message) {
        if (message == null || message.isBlank()) return "来源拒绝了测试请求，请检查密钥权限、配额或来源服务状态。";
        String lower = message.toLowerCase();
        if (lower.contains("key") || lower.contains("auth") || lower.contains("401") || lower.contains("403")) {
            return "来源拒绝认证，请确认已保存正确的官方 API Key、应用权限和额度。";
        }
        if (lower.contains("429") || lower.contains("rate")) return "来源当前限流，请稍后再测试。";
        return "来源暂时未返回可用结果，请检查网络、配额或官方服务状态后重试。";
    }

    private MysqlSettings validate(MysqlRequest request) {
        String host = request.getHost() == null ? "" : request.getHost().trim();
        int port = request.getPort() == null ? 3306 : request.getPort();
        String database = request.getDatabase() == null ? "" : request.getDatabase().trim();
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();
        if (!(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"))) throw new IllegalArgumentException("仅支持本机 MySQL 地址（127.0.0.1 / localhost）");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须在 1–65535 之间");
        if (!DATABASE.matcher(database).matches()) throw new IllegalArgumentException("数据库名只能包含字母、数字、下划线、$ 或 -");
        if (!USERNAME.matcher(username).matches()) throw new IllegalArgumentException("用户名包含不支持的字符");
        if (password.isBlank()) throw new IllegalArgumentException("请输入数据库密码；该值只用于本次测试或保存，不会显示在页面中");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_general_ci&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false&rewriteBatchedStatements=true";
        return new MysqlSettings(url, username, password);
    }

    private Map<String, Object> testFailure(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String category = "connection_failed";
        String message = "无法连接到 MySQL，请检查服务是否已启动、主机和端口。";
        if ("28000".equals(state) || e.getErrorCode() == 1045) {
            category = "authentication_failed";
            message = "MySQL 拒绝认证：请检查用户名、密码和该用户对数据库的权限。";
        } else if (state.startsWith("08")) {
            category = "service_unreachable";
            message = "无法连接 MySQL 服务：请确认服务已启动且端口正确。";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", false);
        result.put("category", category);
        result.put("message", message);
        return result;
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        // LAN serving does not make this administrative endpoint LAN-safe. Only the local
        // machine may configure databases, release records, or restart the running process.
        String remote = request.getRemoteAddr();
        return "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
    }

    private Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return current.getFileName() != null && current.getFileName().toString().equalsIgnoreCase("backend") ? current.getParent() : current;
    }

    private void backup(Path env) throws IOException {
        if (!Files.exists(env)) return;
        String suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.copy(env, env.resolveSibling(".env.backup-" + suffix), StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeEnv(Path env, Map<String, String> updates) throws IOException {
        List<String> existing = Files.exists(env) ? Files.readAllLines(env, StandardCharsets.UTF_8) : List.of();
        Map<String, String> remaining = new LinkedHashMap<>(updates);
        java.util.ArrayList<String> output = new java.util.ArrayList<>();
        for (String line : existing) {
            int separator = line.indexOf('=');
            String key = separator > 0 ? line.substring(0, separator).trim() : "";
            if (remaining.containsKey(key)) {
                output.add(key + "=" + remaining.remove(key));
            } else output.add(line);
        }
        remaining.forEach((key, value) -> output.add(key + "=" + value));
        Files.write(env, output, StandardCharsets.UTF_8);
    }

    private record MysqlSettings(String url, String username, String password) { }
}
