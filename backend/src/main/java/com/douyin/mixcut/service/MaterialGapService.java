package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.CrawlJob;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Material gap analysis and public-source auto-fill.
 *
 * <p>Answers two questions the Studio asks when a dry-run fails:
 * <ol>
 *   <li>What exactly is missing? (gap)</li>
 *   <li>Can we fill it from Wikimedia/Archive without logins or keys? (auto-fill)</li>
 * </ol>
 *
 * <p>Auto-fill only touches public, no-login, whitelisted-open-license sources:
 * Wikimedia Commons and Internet Archive (visual), filtered to the explicit license
 * whitelist CC0 / Public Domain / CC BY. Mixkit is manual-import only (no public API,
 * bot terms unsupported) and is never auto-queried here. It delegates
 * all actual fetching through the existing CrawlJobService import queue and
 * CrawlerGateway search paths, respecting UrlGuard and the ban on cookie/auth
 * sources. No direct downloading at any point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialGapService {

    private final MaterialStore materialRepo;
    private final ProjectRepo projectRepo;
    private final MaterialFolderRepo folderRepo;
    private final MixPlanner planner;
    private final CrawlerGateway crawler;
    private final CrawlJobService crawlJobService;
    private final AppProps props;

    /**
     * Per-source circuit breaker so one dead/blocked public site (timeout, TLS failure, bot block)
     * cannot poison every auto-fill call: after {@link #BREAKER_MAX_FAILURES} consecutive failures
     * a source is skipped for {@link #breakerCooldownMs} instead of being hit again on each request.
     * State is in-memory and per-process; a success (or an empty result) resets the counter.
     */
    static final int BREAKER_MAX_FAILURES = 2;
    static long breakerCooldownMs = 60_000L; // package-private so focused tests can shorten it

    private final Map<String, SourceBreakerState> sourceBreakers = new ConcurrentHashMap<>();

    private static final class SourceBreakerState {
        private int consecutiveFailures;
        private long openedAtMs;

        synchronized boolean isOpen() {
            return openedAtMs != 0 && System.currentTimeMillis() - openedAtMs < breakerCooldownMs;
        }

        synchronized long retryAfterSeconds() {
            if (openedAtMs == 0) return 0;
            long remaining = openedAtMs + breakerCooldownMs - System.currentTimeMillis();
            return Math.max(0, (remaining + 999) / 1000);
        }

        synchronized void onSuccess() {
            consecutiveFailures = 0;
            openedAtMs = 0;
        }

        synchronized void onFailure() {
            consecutiveFailures++;
            if (consecutiveFailures >= BREAKER_MAX_FAILURES) {
                openedAtMs = System.currentTimeMillis();
            }
        }
    }

    private static final Map<String, String> SOURCE_HINTS = Map.of(
            "wikimedia", "可稍后重试；若持续失败，请检查本机网络/防火墙是否放行 commons.wikimedia.org",
            "archive", "可稍后重试；若持续失败，请检查本机网络/防火墙是否放行 archive.org",
            "pexels", "请在能力中心申请并配置自己的 APP_PEXELS_API_KEY；未配置时不会访问 Pexels 或伪造素材结果");

    /** Public-source auto-fill may only repair visual pools; audio remains an explicit user choice. */
    /** Public B-roll may supplement editorial visuals, never a user's product or branded endcard. */
    public static final Set<String> VISUAL_AUTO_FILL_ROLES = Set.of(
            MaterialRole.hook.name(), MaterialRole.body.name(), MaterialRole.celebrity.name());

    /** Structured result returned by the gap endpoint. */
    @Data
    public static class MaterialGapResult {
        private double availableVisualSec;
        private double rawSourceCapacitySec;
        private int visualCount;
        private int totalVisualCount;
        private double requestedMinSec = 50;
        private double requestedMaxSec = 150;
        private Double requestedTargetSec;
        private boolean sufficient;
        private List<String> missingRoles = new ArrayList<>();
        private String projectKeyword;
        private List<Map<String, String>> usablePublicSources = new ArrayList<>();
        private List<String> notes = new ArrayList<>();
        private Map<String, Integer> roleCounts = new LinkedHashMap<>();
        private int excludedBySourceMode;
        private int excludedByFolder;
        private int excludedByStatus;
        private int failedAdmission;
    }

    /** Auto-fill request body. */
    @Data
    public static class AutoFillRequest {
        private Long projectId;
        /** Optional: current mix parameters / project defaults used to derive the search keyword. */
        private MixParams params;
        /** Optional: server-generated project search terms. Browser clients do not supply arbitrary URLs. */
        private String keyword;
        /** Optional: which public sources to use. Defaults to wikimedia, archive (Mixkit 仅手动导入，不在自动填充内). */
        private List<String> sources;
        /** Optional: how many items per source to queue. Default 5. */
        private Integer perSource = 5;
        /** Optional: force re-fill even if sufficient. */
        private Boolean force;
        /** Optional visual roles this request repairs. Omitted requests retain the body fallback. */
        private List<String> roles;
        /** Optional application folder target. Only an existing application folder ID is accepted. */
        private Long folderId;
    }

    /** Auto-fill result: one or more CrawlJobs were queued. */
    @Data
    public static class AutoFillResult {
        private List<Long> crawlJobIds = new ArrayList<>();
        private List<Map<String, Object>> sourceResults = new ArrayList<>();
        private int totalItemsQueued;
        private boolean any = false;
    }

    // ---------------------------------------------------------------
    //  Material Gap
    // ---------------------------------------------------------------

    /**
     * Analyze the gap between what the user wants and what the material pool can actually deliver.
     *
     * @param projectId optional project for keyword extraction and relevance filtering
     * @param params    mix parameters (or null for defaults)
     */
    public MaterialGapResult analyze(Long projectId, MixParams params) {
        MaterialGapResult result = new MaterialGapResult();
        MixParams p = (params != null) ? params.normalized() : new MixParams().normalized();
        Project project = projectId == null ? null : projectRepo.findById(projectId).orElse(null);

        // 1. Gather readable materials and report why otherwise eligible visuals are excluded.
        List<Material> rawMaterials = materialRepo.findAll();
        List<Material> all = MaterialSourcePolicy.allowed(rawMaterials, p);
        result.setExcludedBySourceMode((int) rawMaterials.stream()
                .filter(m -> m.getFileType() != Material.FileType.audio)
                .filter(m -> !MaterialSourcePolicy.allows(m, p)).count());
        result.setFailedAdmission((int) all.stream()
                .filter(m -> m.getFileType() != Material.FileType.audio)
                .filter(m -> m.getStatus() == Material.Status.failed).count());
        result.setExcludedByStatus((int) all.stream()
                .filter(m -> m.getFileType() != Material.FileType.audio)
                .filter(m -> m.getStatus() != Material.Status.ready || m.getFilePath() == null
                        || (m.getFileType() != Material.FileType.image && (m.getDurationSec() == null || m.getDurationSec() < 1.0))).count());
        List<Material> visuals = all.stream()
                .filter(m -> m.getFilePath() != null)
                .filter(m -> m.getStatus() != Material.Status.failed)
                .filter(m -> m.getFileType() != Material.FileType.audio)
                // Keep this admission threshold aligned with MixPlanner.Cursor. A clip that the
                // planner will reject must not inflate the Studio capacity estimate.
                .filter(m -> m.getFileType() == Material.FileType.image
                        || (m.getDurationSec() != null && m.getDurationSec() >= 1.0))
                .toList();

        // 1b. Folder scope: strict folder steps or plain folderIds restrict which
        // visuals the actual render can use. Capacity and sufficiency must respect
        // this scope, otherwise "素材充足" can be reported while strict folder
        // steps would still block rendering.
        List<Material> scopedVisuals = visuals;
        Set<Long> scopeFolderIds = null;
        if (Boolean.TRUE.equals(p.getStrictFolderSequence())
                && p.getFolderReadSteps() != null && !p.getFolderReadSteps().isEmpty()) {
            scopeFolderIds = new HashSet<>();
            for (MixParams.FolderReadStep step : p.getFolderReadSteps()) {
                if (Boolean.FALSE.equals(step.getEnabled())) continue;
                if (step.getFolderId() != null) scopeFolderIds.add(step.getFolderId());
                if ("fallback".equals(step.getShortagePolicy()) && step.getFallbackFolderId() != null) {
                    scopeFolderIds.add(step.getFallbackFolderId());
                }
            }
        } else if (p.getFolderIds() != null && !p.getFolderIds().isEmpty()) {
            scopeFolderIds = new HashSet<>(p.getFolderIds());
        }
        if (scopeFolderIds != null) {
            final Set<Long> scope = scopeFolderIds;
            result.setExcludedByFolder((int) visuals.stream()
                    .filter(m -> m.getFolderId() == null || !scope.contains(m.getFolderId())).count());
            scopedVisuals = visuals.stream()
                    .filter(m -> m.getFolderId() != null && scope.contains(m.getFolderId()))
                    .toList();
        }

        result.setVisualCount(scopedVisuals.size());
        result.setTotalVisualCount((int) all.stream()
                .filter(m -> m.getFileType() != Material.FileType.audio).count());

        // 2. Build pool (scoped visuals + all audio for role analysis) and compute raw capacity
        List<Material> poolBase = new ArrayList<>(scopedVisuals);
        all.stream().filter(m -> m.getFileType() == Material.FileType.audio).forEach(poolBase::add);
        MixPlanner.Pool pool = planner.buildPool(poolBase, p);
        double rawCapacity = 0;
        for (Material m : pool.allVisual()) {
            rawCapacity += m.getFileType() == Material.FileType.image ? 3.0
                    : Math.max(0, m.getDurationSec() == null ? 0 : m.getDurationSec());
        }
        result.setRawSourceCapacitySec(rawCapacity);
        result.setAvailableVisualSec(rawCapacity);

        // 3. Requested duration
        double target = p.getTargetSec() != null ? p.getTargetSec().doubleValue()
                : (p.getTargetDurationSec() != null ? p.getTargetDurationSec().doubleValue() : p.getMinSec().doubleValue());
        result.setRequestedMinSec(p.getMinSec());
        result.setRequestedMaxSec(p.getMaxSec());
        result.setRequestedTargetSec(target > 0 ? target : null);

        // 4. Role counts
        Map<String, Integer> rc = new LinkedHashMap<>();
        for (MaterialRole role : MaterialRole.values()) {
            if (role == MaterialRole.none) continue;
            java.util.stream.Stream<Material> roleScope = (role == MaterialRole.voice || role == MaterialRole.bgm)
                    ? all.stream().filter(m -> m.getFileType() == Material.FileType.audio)
                    : scopedVisuals.stream();
            long count = roleScope.filter(m -> role.equals(m.getRole())).count();
            rc.put(role.name(), (int) count);
        }
        result.setRoleCounts(rc);

        // 5. Which required roles are empty?
        List<String> missing = new ArrayList<>();
        if (pool.getHook().isEmpty() && pool.getBody().isEmpty() && pool.getCelebrity().isEmpty()
                && pool.getProduct().isEmpty() && pool.getEndcard().isEmpty()) {
            // No visual at all
            if (p.getHookSec() != null && p.getHookSec() > 0) missing.add("hook");
            missing.add("body");
        } else {
            if (p.getHookSec() != null && p.getHookSec() > 0 && pool.getHook().isEmpty()
                    && pool.getBody().isEmpty()) missing.add("hook");
            if (pool.getBody().isEmpty() && pool.getCelebrity().isEmpty()) missing.add("body");
            if (p.getProductSlots() != null
                    && p.getProductSlots() > 0 && pool.getProduct().isEmpty()) missing.add("product");
            if (p.getCelebrityRatio() != null && p.getCelebrityRatio() > 0
                    && pool.getCelebrity().isEmpty()) missing.add("celebrity");
            if (Boolean.TRUE.equals(p.getEndcard()) && Boolean.TRUE.equals(p.getRequireDedicatedEndcard())
                    && pool.getEndcard().isEmpty()) missing.add("endcard");
        }

        // Only material-audio needs imported voice/BGM. Original audio, silence and AI narration
        // have their own render paths and must not be reported as missing local audio roles.
        boolean materialAudio = "material-audio".equalsIgnoreCase(p.getAudioMode());
        if (materialAudio) {
            boolean wantsVoice = p.getVoiceMaterialId() != null || p.getHookAudioMaterialId() != null;
            if (wantsVoice && pool.getVoice().isEmpty() && pool.getBgm().isEmpty()) missing.add("voice-bgm");
            if (p.getVoiceMaterialId() != null && pool.getVoice().isEmpty()) missing.add("voice");
            if (p.getBgmMaterialId() != null && pool.getBgm().isEmpty()) missing.add("bgm");
        }

        result.setMissingRoles(missing);

        // 6. Project keyword for search
        String keyword = buildKeyword(project, p);
        result.setProjectKeyword(keyword);

        // 7. Sufficiency check (folder-scope aware)
        // Static images without an explicit role are deliberately excluded by the planner.
        // Require a renderable planner pool instead of merely a non-empty library scope.
        boolean sufficient = rawCapacity + 0.5 >= p.getMinSec() && pool.hasVisual();
        result.setSufficient(sufficient);

        // 8. Usable public sources（仅免登录自动源；Mixkit 只支持手动导入，不在此列出）
        List<Map<String, String>> sources = new ArrayList<>();
        sources.add(Map.of("key", "wikimedia", "name", "Wikimedia Commons",
                "type", "video", "needKey", "false", "note", "公开许可视频（CC0/公有领域/CC BY），免 API Key"));
        sources.add(Map.of("key", "archive", "name", "Internet Archive",
                "type", "video", "needKey", "false", "note", "CC0 / 公有领域 / CC BY 视频片段"));
        if (props.getPexelsApiKey() != null && !props.getPexelsApiKey().isBlank()) {
            sources.add(Map.of("key", "pexels", "name", "Pexels 官方 API",
                    "type", "video", "needKey", "configured", "note", "已读取本机官方 API Key，可作为授权视频来源"));
        } else {
            sources.add(Map.of("key", "pexels", "name", "Pexels 官方 API",
                    "type", "video", "needKey", "true", "note", "需在能力中心配置自己的 Pexels API Key"));
        }
        result.setUsablePublicSources(sources);

        // 9. Notes
        if (!sufficient) {
            result.getNotes().add(String.format(Locale.ROOT,
                    "当前可读画面素材约 %.1f 秒，低于目标下限 %d 秒。建议通过素材抓取或本地扫描补充。",
                    rawCapacity, p.getMinSec()));
        }
        if (!missing.isEmpty()) {
            List<String> publicFill = missing.stream().filter(VISUAL_AUTO_FILL_ROLES::contains).toList();
            List<String> localOnly = missing.stream()
                    .filter(role -> MaterialRole.product.name().equals(role) || MaterialRole.endcard.name().equals(role)).toList();
            if (!publicFill.isEmpty()) {
                result.getNotes().add("可由公开素材补齐的角色：" + String.join("、", publicFill)
                        + "。仅使用许可可验证、免登录的公开视频来源。");
            }
            if (!localOnly.isEmpty()) {
                result.getNotes().add("必须由本地素材提供的角色：" + String.join("、", localOnly)
                        + "。公开 B-roll 不会替代产品、品牌或片尾素材。");
            }
        }
        if (Boolean.TRUE.equals(p.getEndcard()) && pool.getEndcard().isEmpty()
                && !Boolean.TRUE.equals(p.getRequireDedicatedEndcard())) {
            result.getNotes().add("未提供独立片尾卡：将优先使用产品图，其次使用合格主体镜头作为收尾；建议后续补充产品图、品牌 Logo、优惠图、二维码图或 3–5 秒收尾视频。");
        }
        if (scopedVisuals.size() < 3) {
            result.getNotes().add("画面素材过少（少于 3 条），混剪效果受限。");
        }
        if (result.getExcludedBySourceMode() > 0) result.getNotes().add("有 " + result.getExcludedBySourceMode() + " 条已抓取素材被当前“仅本地素材”范围排除；切换到公开素材范围后可重新检查。");
        if (result.getExcludedByFolder() > 0) result.getNotes().add("有 " + result.getExcludedByFolder() + " 条可读画面不在当前授权文件夹范围；可绑定目标文件夹后重新检查。");
        if (result.getFailedAdmission() > 0) result.getNotes().add("有 " + result.getFailedAdmission() + " 条下载素材未通过质量准入；可在素材库重新检测或替换。");
        if (scopedVisuals.isEmpty()) {
            result.getNotes().add("尚未导入任何可读画面素材，或当前文件夹范围内没有可用素材。请调整文件夹范围、本地扫描或使用公开源抓取。");
        } else if (!pool.hasVisual()) {
            result.getNotes().add("当前范围内只有未分类静态图片；自动规划不会把它们当作主体画面。请标记为产品、钩子、明星或片尾素材，或导入至少 1 秒的视频素材。");
        }

        return result;
    }

    // ---------------------------------------------------------------
    //  Auto-Fill (Public, No-Login Sources Only)
    // ---------------------------------------------------------------

    /**
     * Queue auto-fill crawl jobs for public sources.
     *
     * <p>Only invokes existing no-login, whitelisted-open-license crawler search/import
     * queue mechanisms:
     * <ul>
     *   <li>Wikimedia Commons visual search -> CrawlJobService.submitVideoItems</li>
     *   <li>Internet Archive visual search -> CrawlJobService.submitVideoItems</li>
     * </ul>
     *
     * <p>Each source is searched with the project keyword; the top relevance-rated
     * items are queued through the existing import pipeline. No direct downloading.
     * Sources requiring keys (Freesound, Pixabay) are never auto-queried, and Mixkit
     * (manual-import only) is never auto-queried either — an explicit request for it
     * returns a manual_only hint instead of silently queuing anything.</p>
     *
     * <p>Notice/placeholder items are never queued: if a source responds only with
     * notices (e.g. an explanatory tip), that is reported as a per-source failure with
     * the notice text and a remediation hint — never as a queued success.</p>
     *
     * <p>Per-source execution is bounded by a small circuit breaker: a source that fails
     * {@value #BREAKER_MAX_FAILURES} times in a row is skipped for the cooldown window so a
     * blocked/unreachable site does not stall or poison every auto-fill request. Failures are
     * reported per source with a redacted cause and a remediation hint instead of a bare
     * exception message.</p>
     */
    public AutoFillResult autoFill(AutoFillRequest req) {
        AutoFillResult result = new AutoFillResult();
        List<String> sources = (req.getSources() == null || req.getSources().isEmpty())
                ? defaultSources()
                : req.getSources();
        int perSource = Math.min(20, Math.max(1, req.getPerSource() == null ? 5 : req.getPerSource()));
        Project project = req.getProjectId() == null ? null
                : projectRepo.findById(req.getProjectId()).orElse(null);
        String keyword = req.getKeyword() == null || req.getKeyword().isBlank()
                ? buildKeyword(project, req.getParams())
                : req.getKeyword().trim();
        if (keyword.length() > 160) keyword = keyword.substring(0, 160);
        List<String> roles = approvedVisualRoles(req.getRoles());
        List<String> localRequired = requestedLocalOnlyRoles(req.getRoles());
        if (!localRequired.isEmpty()) {
            result.getSourceResults().add(Map.of(
                    "source", "local-library",
                    "status", "local_required",
                    "roles", String.join(",", localRequired),
                    "message", "产品和片尾素材必须来自本地素材库或已授权导入，公开 B-roll 不会替代这些角色。"));
        }
        if (roles.isEmpty()) {
            result.setAny(false);
            return result;
        }
        Long folderId = req.getFolderId();
        if (folderId != null && folderRepo.findById(folderId).isEmpty()) throw new IllegalArgumentException("自动补齐目标文件夹不存在");

        for (String source : sources) {
            runSource(source.toLowerCase(Locale.ROOT), keyword, perSource, project, roles, folderId, result);
        }

        result.setAny(result.getTotalItemsQueued() > 0);
        return result;
    }

    /** Executes one source with breaker gating and appends an actionable per-source result. */
    private void runSource(String s, String keyword, int perSource, Project project, List<String> roles, Long folderId, AutoFillResult result) {
        long started = System.nanoTime();
        SourceBreakerState breaker = sourceBreakers.computeIfAbsent(s, k -> new SourceBreakerState());
        if (breaker.isOpen()) {
            result.getSourceResults().add(Map.of(
                    "source", s,
                    "status", "skipped_breaker",
                    "message", "该来源近期连续失败，已临时熔断跳过，请在冷却结束后重试",
                    "retryAfterSeconds", breaker.retryAfterSeconds()));
            return;
        }
        try {
            if ("wikimedia".equals(s) || "archive".equals(s) || "pexels".equals(s)) {
                List<CrawlerGateway.RemoteItem> items = crawler.searchPublicVideoQuick(s, keyword, perSource, project);
                // 提示/占位条目（notice）绝不能作为素材入库：只排队真实可下载条目
                List<CrawlerGateway.RemoteItem> usable = items.stream()
                        .filter(i -> i != null && !i.isNotice()).toList();
                String fallbackKeyword = crawler.publicVideoSearchKeyword(keyword);
                boolean fallbackUsed = false;
                // Public indexes mostly expose English metadata. Keep the project-scoped query first,
                // then use the same approved source once with a bounded class/scene fallback.
                if (usable.isEmpty() && project != null && !fallbackKeyword.isBlank()) {
                    usable = crawler.searchPublicVideoQuick(s, fallbackKeyword, perSource, null).stream()
                            .filter(i -> i != null && !i.isNotice()).toList();
                    fallbackUsed = !usable.isEmpty();
                }
                if (!usable.isEmpty()) {
                    Map<String, List<CrawlerGateway.RemoteItem>> itemsByRole = new LinkedHashMap<>();
                    for (int i = 0; i < usable.size(); i++) {
                        String role = roles.get(i % roles.size());
                        itemsByRole.computeIfAbsent(role, ignored -> new ArrayList<>()).add(usable.get(i));
                    }
                    for (Map.Entry<String, List<CrawlerGateway.RemoteItem>> entry : itemsByRole.entrySet()) {
                        CrawlJob job = folderId == null
                                ? crawlJobService.submitVideoItems(entry.getValue(), entry.getKey())
                                : crawlJobService.submitVideoItems(entry.getValue(), entry.getKey(), folderId);
                        result.getCrawlJobIds().add(job.getId());
                        Map<String, Object> sourceResult = new LinkedHashMap<>();
                        sourceResult.put("source", s);
                        sourceResult.put("role", entry.getKey());
                        sourceResult.put("items", entry.getValue().size());
                        sourceResult.put("jobId", job.getId());
                        sourceResult.put("status", "queued");
                        if (fallbackUsed) {
                            sourceResult.put("fallbackKeyword", fallbackKeyword);
                            sourceResult.put("message", "项目词未命中英文公开索引，已用合规类目/场景词回退检索：" + fallbackKeyword);
                        }
                        result.getSourceResults().add(sourceResult);
                        result.setTotalItemsQueued(result.getTotalItemsQueued() + entry.getValue().size());
                    }
                } else if (items.stream().anyMatch(i -> i != null && i.isNotice())) {
                    // 来源只回了提示（如被合规拦截）：按失败上报提示原文，绝不报“无结果”假成功
                    breaker.onFailure();
                    String noticeMsg = items.stream().filter(i -> i != null && i.isNotice())
                            .map(CrawlerGateway.RemoteItem::getTitle)
                            .filter(t -> t != null && !t.isBlank())
                            .findFirst().orElse("来源返回了提示条目，未产出可入库素材");
                    result.getSourceResults().add(Map.of(
                            "source", s,
                            "status", "failed",
                            "message", noticeMsg + "。" + hintFor(s),
                            "retryAfterSeconds", breaker.retryAfterSeconds()));
                } else {
                    result.getSourceResults().add(Map.of("source", s,
                            "items", 0, "status", "no_results"));
                }
            } else if ("mixkit".equals(s)) {
                // Mixkit 无公开 API 且服务条款不支持无人值守抓取：只允许手动导入
                result.getSourceResults().add(Map.of("source", "mixkit",
                        "status", "manual_only",
                        "message", "Mixkit 无公开 API 且其服务条款不支持无人值守抓取，仅支持手动导入：请在素材抓取页检索后人工确认导入。"));
                return; // 不是一次自动尝试，不触碰熔断器
            } else {
                result.getSourceResults().add(Map.of("source", s,
                        "status", "unsupported",
                        "message", "该来源不在自动填充范围内。自动填充支持：wikimedia, archive，以及已配置官方 Key 的 pexels；Mixkit 仅支持手动导入。"));
                return; // 不是一次自动尝试，不触碰熔断器
            }
            breaker.onSuccess(); // queued or empty: source responded, reset any failure count
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("auto-fill source {} failed: {}", s, CrawlerGateway.safeError(e));
            Map<String, Object> failure = new HashMap<>();
            failure.put("source", s);
            failure.put("status", "failed");
            failure.put("message", "检索失败：" + CrawlerGateway.safeError(e) + "。" + hintFor(s));
            failure.put("retryAfterSeconds", breaker.retryAfterSeconds());
            failure.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            result.getSourceResults().add(failure);
        }
    }

    private static String hintFor(String source) {
        String hint = SOURCE_HINTS.get(source);
        return hint == null ? "可稍后重试" : hint;
    }

    private List<String> defaultSources() {
        if (props.getPexelsApiKey() == null || props.getPexelsApiKey().isBlank()) {
            return List.of("wikimedia", "archive");
        }
        return List.of("wikimedia", "archive", "pexels");
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private List<String> approvedVisualRoles(List<String> requestedRoles) {
        if (requestedRoles == null || requestedRoles.isEmpty()) return List.of(MaterialRole.body.name());
        List<String> roles = new ArrayList<>();
        for (String role : requestedRoles) {
            if (role != null && VISUAL_AUTO_FILL_ROLES.contains(role) && !roles.contains(role)) {
                roles.add(role);
            }
        }
        return List.copyOf(roles);
    }

    private List<String> requestedLocalOnlyRoles(List<String> requestedRoles) {
        if (requestedRoles == null || requestedRoles.isEmpty()) return List.of();
        List<String> roles = new ArrayList<>();
        for (String role : requestedRoles) {
            if ((MaterialRole.product.name().equals(role) || MaterialRole.endcard.name().equals(role)) && !roles.contains(role)) {
                roles.add(role);
            }
        }
        return List.copyOf(roles);
    }

    private String buildKeyword(Project project, MixParams params) {
        if (project != null) {
            StringBuilder sb = new StringBuilder();
            if (project.getCategory() != null && !project.getCategory().isBlank()) {
                sb.append(project.getCategory().trim());
            }
            if (project.getProduct() != null && !project.getProduct().isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(project.getProduct().trim());
            }
            if (project.getBrand() != null && !project.getBrand().isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(project.getBrand().trim());
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        if (params != null && params.getNamePrefix() != null && !params.getNamePrefix().isBlank()
                && !"mix".equals(params.getNamePrefix())) {
            return params.getNamePrefix();
        }
        return "video clip b-roll";
    }
}
