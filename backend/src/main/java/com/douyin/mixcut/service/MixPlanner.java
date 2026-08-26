package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.HookStrategy;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.MaterialSegment;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.repository.MaterialAnalysisStore;
import com.douyin.mixcut.repository.MaterialSegmentStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 剪辑计划生成器（不碰 ffmpeg，纯算法，可单测、可给前端预览、可让用户手动微调）。
 *
 * <p>把「素材池 + 参数」变成一条明确的时间线：第几秒放哪个文件的哪一段。
 * 这是"半自动"的关键 —— 计划先出来给人看一眼，确认没问题再渲染，
 * 而不是黑盒跑完 20 分钟才发现产品段没进去。
 *
 * <p><b>三条硬规则</b>（踩过坑，写死在这里）：
 * <ol>
 *   <li><b>永不复用同一个 Segment 对象</b>。素材不够时靠"换起点再切"生成新片段，
 *       而不是把同一段塞两次 —— 逐帧重复的内容在抖音会被判重、限流。</li>
 *   <li><b>所有起点+时长都必须落在素材真实时长内</b>。越界 seek 会产出黑帧/静止画面。</li>
 *   <li><b>相邻两段尽量不同素材</b>。连着两段同一个文件，观感上就是"卡住了"。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MixPlanner {

    /** 语义候选切片（来自已完成的素材结构化分析），回放区间而非磁盘文件。 */
    public record SemanticSlice(double start, double duration) {
    }

    /** 钩子策略与中段再钩子文案（由 SkillEngine / JobService 在计划前注入，MixPlanner 负责安排时间窗）。 */
    @Data
    public static class HookProfile {
        private String strategy;
        private String rehookText;
    }

    /** 可选依赖：字段注入且可为空，保证纯算法单测无需 Spring 容器或数据库。 */
    @Autowired(required = false)
    private MaterialSegmentStore segmentStore;
    @Autowired(required = false)
    private MaterialAnalysisStore analysisStore;

    private final Map<Long, SemanticCacheEntry> semanticCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SEMANTIC_CACHE_TTL_MS = 60_000L;

    private static final class SemanticCacheEntry {
        final List<MaterialSegment> segments;
        final long at;
        SemanticCacheEntry(List<MaterialSegment> segments, long at) {
            this.segments = segments == null ? List.of() : segments;
            this.at = at;
        }
    }

    /** 时间线上的一段 */
    @Data
    public static class Segment {
        private int index;
        private Long materialId;
        private String materialName;
        private String filePath;
        /** Disabled editor rows remain in the snapshot but are skipped by rendering. */
        private boolean enabled = true;
        /** video / image */
        private String kind;
        /** 源文件内的起点 */
        private double sourceStart;
        private double duration;
        /** 源素材总时长，用于越界校验（前端也会显示，方便人工核对） */
        private double sourceDuration;
        /** hook / body / celebrity / product / endcard */
        private String slot;
    }

    @Data
    public static class Plan {
        private double targetSec;
        /** User-selected delivery lower bound. targetSec remains an optimization preference only. */
        private double minSec;
        private double plannedSec;
        private List<Segment> segments = new ArrayList<>();
        private Long voiceMaterialId;
        private String voicePath;
        /** Total scheduled narration coverage, which may span several distinct source files. */
        private double voiceDurationSec;
        private List<VoiceSegment> voiceSegments = new ArrayList<>();
        private Long bgmMaterialId;
        private String bgmPath;
        private double bgmDurationSec;
        private Long hookAudioMaterialId;
        private String hookAudioPath;
        /** True for material-audio mode, which must provide a real BGM/voice/hook audio source. */
        private boolean requiresExternalAudio;
        /** Real timeline range of the hook visual, after any custom intro. */
        private double hookStartSec;
        private double hookEndSec;
        private String hookText;
        private long seed;
        private List<String> notes = new ArrayList<>();
        /** False when standard/strict de-duplication finds repeated footage inside this one timeline. */
        private boolean internallyUnique = true;
        /** Deterministic marketing structure audit data shown before rendering. */
        private String marketingStructure;
        private String marketingStructurePreview;
        private List<MarketingStructurePlanner.Stage> marketingStages = new ArrayList<>();

        /** 本条成片实际使用的钩子策略（explainable dry-run 展示）。 */
        private String hookStrategy;
        /** 中段再钩子文案与时间窗（mid-roll rehook），渲染器可烧录且不影响既有钩子字幕。 */
        private String rehookText;
        private double rehookWindowStart;
        private double rehookWindowEnd;
        /** timeline 中实际使用的 scene 镜头数与网格回退镜头数，不是素材条数。 */
        private int semanticSegmentCount;
        private int gridFallbackCount;
        /** 每个回退素材/原因，供 Preflight 与 DeliveryQc 解释降级。 */
        private List<String> fallbackReasons = new ArrayList<>();
        /** 实际命中的确定性语义证据，供调用方审计。 */
        private List<SemanticEvidenceMatcher.Evidence> semanticMatches = new ArrayList<>();
        /** 有 completed+scene 分析结论；false 表示分析不可用，严格模式不得仅因此阻断。 */
        private boolean semanticAnalysisAvailable;
        /** True once planner has audited the actual timeline; distinguishes unavailable analysis from legacy hand-built plans. */
        private boolean semanticAuditApplied;

        /** Real ASR-derived captions for the AI narration voice, already in video-timeline seconds. */
        private List<CaptionCue> narrationCaptions = new ArrayList<>();
        private String narrationScriptText;
        /** True when BGM was ducked under a prioritized human voice. */
        private Boolean duckBgm;

        /** Stable source-slice keys used to prevent duplicate footage across one job. */
        public Set<String> segmentKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (Segment segment : segments) {
                keys.add(segment.getMaterialId() + "@" + roundKey(segment.getSourceStart()) + "+" + roundKey(segment.getDuration()));
            }
            return keys;
        }

        /**
         * Fuzzy keys for near-overlap detection: rounds start/duration to 0.5s granularity,
         * so two segments that merely differ by a few frames are treated as overlapping.
         */
        public Set<String> fuzzySegmentKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (Segment segment : segments) {
                double fuzzyStart = Math.round(segment.getSourceStart() * 2.0) / 2.0;
                double fuzzyDur = Math.round(segment.getDuration() * 2.0) / 2.0;
                keys.add(segment.getMaterialId() + "@" + String.format(Locale.ROOT, "%.1f", fuzzyStart)
                        + "+" + String.format(Locale.ROOT, "%.1f", fuzzyDur));
            }
            return keys;
        }

        /** Check whether this plan has near-overlapping segments with a previously used key set. */
        public boolean hasNearOverlap(Set<String> usedFuzzyKeys, double overlapThreshold) {
            Set<String> mine = fuzzySegmentKeys();
            Set<String> overlap = new java.util.HashSet<>(mine);
            overlap.retainAll(usedFuzzyKeys);
            return overlap.size() > mine.size() * overlapThreshold;
        }

        private String roundKey(double value) {
            return String.format(Locale.ROOT, "%.3f", value);
        }

        /**
         * Shared delivery contract for dry-run, batch jobs, and rendering.
         * Jackson recognizes this Bean-style getter and returns it to the Studio client as `usable`.
         */
        public boolean isUsable() {
            return !segments.isEmpty() && plannedSec >= minSec && internallyUnique;
        }

        /** Compatibility for internal callers while the API uses the JSON-visible isUsable getter. */
        public boolean usable() { return isUsable(); }

        /** Per-role usage tracking for least-used rotation across a batch of plans. */
        public Map<String, Integer> audioUsage = new HashMap<>();
        public Set<String> usedHookVoices = new HashSet<>();

        /** One real ASR timestamp cue for AI narration subtitles, in video-timeline seconds. */
        @Data
        public static class VoiceSegment {
            private Long materialId;
            private String materialName;
            private String filePath;
            private double timelineStart;
            private double duration;
            private double sourceStart;
            private double sourceDuration;
        }

        @Data
        public static class CaptionCue {
            private double start;
            private double end;
            private String text;
        }
    }

    /** 素材池：按角色分好组 */
    @Data
    public static class Pool {
        private List<Material> hook = new ArrayList<>();
        private List<Material> body = new ArrayList<>();
        private List<Material> celebrity = new ArrayList<>();
        private List<Material> product = new ArrayList<>();
        private List<Material> endcard = new ArrayList<>();
        private List<Material> voice = new ArrayList<>();
        private List<Material> bgm = new ArrayList<>();

        public boolean hasVisual() {
            return !hook.isEmpty() || !body.isEmpty() || !celebrity.isEmpty()
                    || !product.isEmpty() || !endcard.isEmpty();
        }

        public List<Material> allVisual() {
            List<Material> l = new ArrayList<>();
            l.addAll(hook);
            l.addAll(body);
            l.addAll(celebrity);
            l.addAll(product);
            l.addAll(endcard);
            return l;
        }
    }

    /**
     * Content-driven audio selection hint derived from the project editorial brief.
     * Null-safe: when {@link #isPresent()} is false, all existing audio fallbacks still apply.
     */
    @Data
    public static class AudioIntent {
        /** Preferred BGM mood keywords, matched case-insensitively against BGM name/tags. */
        private List<String> moodKeywords = new ArrayList<>();
        /** Human narration should stay at full volume while BGM is ducked. */
        private boolean preferHumanVoice = true;
        private boolean duckBgm = true;
        /** True when the brief carried at least one usable mood keyword. */
        private boolean present;

        public static AudioIntent none() {
            AudioIntent intent = new AudioIntent();
            intent.setPresent(false);
            return intent;
        }
    }

    public Pool buildPool(List<Material> materials) {
        return buildPool(materials, new MixParams());
    }

    public Pool buildPool(List<Material> materials, MixParams params) {
        Pool p = new Pool();
        for (Material m : materials) {
            if (!MaterialSourcePolicy.allows(m, params)) continue;
            if (m.getFilePath() == null) continue;
            if (m.getStatus() == Material.Status.failed) continue;
            boolean audio = m.getFileType() == Material.FileType.audio;
            boolean image = m.getFileType() == Material.FileType.image;
            if (!audio && !image && (m.getDurationSec() == null || m.getDurationSec() < 1.0)) continue;

            MaterialRole r = m.getRole() == null ? MaterialRole.none : m.getRole();
            if (audio) {
                if (r == MaterialRole.voice) p.voice.add(m);
                else p.bgm.add(m);
                continue;
            }
            if (image) {
                // 静态图片不是自动 B-roll 候选：只按显式角色进入 hook/celebrity/product/endcard 槽位，
                // 不再落入 body（自动流水线不应拿静态图当主体画面）。素材库记录保留，人工仍可管理/复用。
                switch (r) {
                    case hook -> p.hook.add(m);
                    case celebrity -> p.celebrity.add(m);
                    case product -> p.product.add(m);
                    case endcard -> p.endcard.add(m);
                    default -> { }
                }
                continue;
            }
            switch (r) {
                case hook -> p.hook.add(m);
                case celebrity -> p.celebrity.add(m);
                case product -> p.product.add(m);
                case endcard -> p.endcard.add(m);
                case body -> p.body.add(m);
                default -> p.body.add(m);
            }
        }
        return p;
    }

    // ==================================================================
    //  取片游标：一个素材一个游标，负责源源不断吐出「不重复」的切片
    // ==================================================================

    /**
     * 一条素材的切片游标。
     *
     * <p>把 15s 素材按 3s 切成 5 个网格起点 [0,3,6,9,12]，按顺序吐。
     * 吐完一轮后进入第 2 轮，整体加一个亚秒级错位（0.7s、1.4s…），
     * 于是第 2 轮的 0.7-3.7s 和第 1 轮的 0-3s 画面并不相同 —— 素材被"榨"出更多变化，
     * 但绝不会出现两段一模一样的内容。
     */
    private static final class Cursor {
        final Material m;
        final double dur;
        final String slot;
        final List<Double> grid = new ArrayList<>();
        final List<SemanticSlice> semantic;
        int ptr = 0;
        int semanticPtr = 0;
        int usageCount = 0;
        /** 已吐出的 (起点,时长)，用于兜底去重 */
        final Set<String> emitted = new HashSet<>();
        /** 已吐出的 [start,end) 区间，用于严格同源不重叠校验 */
        final List<double[]> emittedIntervals = new ArrayList<>();

        Cursor(Material m, String slot, double sliceSec, int variant) {
            this(m, slot, sliceSec, variant, null);
        }

        Cursor(Material m, String slot, double sliceSec, int variant, List<SemanticSlice> semantic) {
            this.m = m;
            this.slot = slot;
            this.dur = m.getDurationSec() == null ? 0 : m.getDurationSec();
            this.semantic = semantic == null ? new ArrayList<>() : new ArrayList<>(semantic);
            if (!this.semantic.isEmpty()) {
                int shift = Math.floorMod(variant * 2, this.semantic.size());
                Collections.rotate(this.semantic, -shift);
            }
            int n = (int) Math.floor(dur / Math.max(0.5, sliceSec));
            if (n <= 0) {
                grid.add(0.0);
            } else {
                for (int i = 0; i < n; i++) grid.add(i * sliceSec);
                // variant 位移：第 1 条成片从 0 开始，第 2 条从第 2 格开始……
                // 老板要的"这条用前 3 秒，下一条用后面 3 秒"就是这里实现的
                int shift = Math.floorMod(variant * 2, grid.size());
                Collections.rotate(grid, -shift);
            }
        }

        boolean exhaustedRounds(int maxRounds) {
            if (!semantic.isEmpty()) {
                return semanticPtr >= semantic.size();
            }
            return grid.isEmpty() || ptr >= grid.size() * maxRounds;
        }
    }

    private List<Cursor> buildCursors(List<Material> src, String slot, MixParams p, int variant,
                                      Map<Long, List<MaterialSegment>> semantic) {
        List<Cursor> out = new ArrayList<>();
        for (Material m : src) {
            double d = m.getDurationSec() == null ? 0 : m.getDurationSec();
            if (m.getFileType() != Material.FileType.image && d < 1.0) continue;
            List<SemanticSlice> slices = semantic == null ? null : semantic.getOrDefault(m.getId(), List.of())
                    .stream()
                    .filter(seg -> seg.getStartSec() != null && seg.getDurationSec() != null
                            && seg.getDurationSec() >= 0.8)
                    .sorted(Comparator.comparing(MaterialSegment::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(MaterialSegment::getStartSec)
                            .thenComparing(MaterialSegment::getDurationSec))
                    .map(seg -> new SemanticSlice(seg.getStartSec(), seg.getDurationSec()))
                    .toList();
            out.add(new Cursor(m, slot, p.getSliceSec(), variant, slices));
        }
        return out;
    }

    /** 从游标吐一个新切片；吐不出来（越界/太短）返回 null */
    private Segment emit(Cursor c, MixParams p, Random rnd, double lastDuration) {
        if (c.m.getFileType() == Material.FileType.image) {
            Segment s = base(c.m, c.slot);
            s.setKind("image");
            s.setSourceStart(0);
            s.setSourceDuration(0);
            s.setDuration(smoothJitter(p, rnd, lastDuration));
            c.ptr++;
            c.usageCount++;
            return s;
        }

        // 语义候选优先：有结构化镜头片段时，逐个按镜头边界取片；用完即视为该素材耗尽。
        if (!c.semantic.isEmpty()) {
            return emitSemantic(c, p);
        }
        if (c.grid.isEmpty()) return null;

        int round = c.ptr / c.grid.size();
        double gridStart = c.grid.get(c.ptr % c.grid.size());
        c.ptr++;

        // 第 N 轮整体错位，避免与前几轮画面重合
        double shift = round == 0 ? 0
                : Math.min(p.getSliceSec() * 0.9, round * 0.7 + rnd.nextDouble() * 0.5);
        double d = smoothJitter(p, rnd, lastDuration);
        double start = gridStart + shift;

        // 越界校正：宁可往前挪，也不能 seek 到素材尾巴外面去
        double hardMax = c.dur - 0.08;
        if (start + d > hardMax) start = hardMax - d;
        if (start < 0) {
            start = 0;
            d = Math.min(d, hardMax);
        }
        if (d < 0.8) return null;

        double strictStart = resolveStrictStart(c, p, start, d, hardMax);
        if (Double.isNaN(strictStart)) return null;
        start = strictStart;

        start = round(start);
        d = round(d);
        if (!c.emitted.add(start + "/" + d)) {
            // 极端情况下撞了，微调 0.25s 再试一次
            start = round(Math.max(0, Math.min(hardMax - d, start + 0.25)));
            if (!c.emitted.add(start + "/" + d)) return null;
        }

        c.emittedIntervals.add(new double[]{start, start + d});
        c.usageCount++;
        Segment s = base(c.m, c.slot);
        s.setSourceStart(start);
        s.setDuration(d);
        s.setSourceDuration(round(c.dur));
        return s;
    }

    /** 语义切片发射：按镜头边界取片，遵守越界与严格同源不重叠。 */
    private Segment emitSemantic(Cursor c, MixParams p) {
        if (c.semanticPtr >= c.semantic.size()) return null;
        SemanticSlice slice = c.semantic.get(c.semanticPtr++);
        double hardMax = c.dur - 0.08;
        if (hardMax <= 0.8) return null;
        double d = Math.max(0.8, Math.min(slice.duration(), p.getMaxSegmentSec()));
        double start = Math.max(0, Math.min(slice.start(), Math.max(0, hardMax - d)));

        double strictStart = resolveStrictStart(c, p, start, d, hardMax);
        if (Double.isNaN(strictStart)) return null;
        start = strictStart;
        if (start + d > hardMax) {
            d = Math.max(0.8, hardMax - start);
            if (d < 0.8 || start + d > hardMax) return null;
        }

        start = round(start);
        d = round(d);
        if (!c.emitted.add(start + "/" + d)) {
            start = round(Math.max(0, Math.min(hardMax - d, start + 0.25)));
            if (!c.emitted.add(start + "/" + d)) return null;
        }
        c.emittedIntervals.add(new double[]{start, start + d});
        c.usageCount++;
        Segment s = base(c.m, c.slot);
        s.setSourceStart(start);
        s.setDuration(d);
        s.setSourceDuration(round(c.dur));
        return s;
    }

    /** 严格去重下，保证同一素材源吐出的片段时间不重叠；无法安排时返回 NaN。 */
    private double resolveStrictStart(Cursor c, MixParams p, double start, double d, double hardMax) {
        if (!"strict".equalsIgnoreCase(p.getDedupStrictness())) return start;
        for (double[] iv : c.emittedIntervals) {
            if (start < iv[1] && start + d > iv[0]) {
                start = iv[1] + 0.05;
                if (start + d > hardMax) return Double.NaN;
            }
        }
        return start;
    }

    /**
     * 轮询取片，并保证「相邻不同素材」。
     *
     * @param cursors    候选游标
     * @param lastMid    上一段用的素材 id
     * @param maxRounds  每个素材最多榨几轮
     */
    private Segment takeNext(List<Cursor> cursors, Long lastMid, MixParams p, Random rnd, int maxRounds, double lastDuration) {
        if (cursors.isEmpty()) return null;
        // 先挑「还有余量 且 不是上一段那个素材」的
        List<Cursor> avail = new ArrayList<>();
        for (Cursor c : cursors) if (!c.exhaustedRounds(maxRounds)) avail.add(c);
        if (avail.isEmpty()) return null;

        List<Cursor> prefer = new ArrayList<>();
        for (Cursor c : avail) if (!Objects.equals(c.m.getId(), lastMid)) prefer.add(c);
        List<Cursor> use = prefer.isEmpty() ? avail : prefer;

        // 用得最少的优先，出镜更均匀；同 ptr 时随机打散
        // Generate one random tie-breaker per cursor before sorting. Calling Random from
        // a comparator violates TimSort's ordering contract and can crash dry-run planning.
        Map<Cursor, Integer> randomOrder = new IdentityHashMap<>();
        for (Cursor cursor : use) randomOrder.put(cursor, rnd.nextInt());
        use.sort(Comparator.comparingInt((Cursor c) -> c.ptr)
                .thenComparingInt((Cursor c) -> c.usageCount)
                .thenComparingInt(randomOrder::get)
                .thenComparing(c -> c.m.getId(), Comparator.nullsLast(Comparator.naturalOrder())));

        for (Cursor c : use) {
            Segment s = emit(c, p, rnd, lastDuration);
            if (s != null) return s;
        }
        return null;
    }

    // ==================================================================
    //  主流程
    // ==================================================================

    /**
     * 生成第 variant 条成片的计划。variant 不同 → 切片位移不同 → 每条都不一样。
     */
    public Plan plan(Pool pool, MixParams params, int variant, String hookText) {
        return plan(pool, params, variant, hookText, null);
    }

    /**
     * 生成第 variant 条成片的计划。variant 不同 → 切片位移不同 → 每条都不一样。
     * {@code intent} 为可空的内容驱动音频意图；为空或未命中时沿用原有音频兜底逻辑。
     */
    public Plan plan(Pool pool, MixParams params, int variant, String hookText, AudioIntent intent) {
        return plan(pool, params, variant, hookText, intent, null);
    }

    /**
     * 包私有主实现。{@code semanticOverride} 用于单测注入结构化片段；为 null 时从已注入的
     * {@link MaterialSegmentStore}/{@link MaterialAnalysisStore} 惰性加载并缓存。
     */
    Plan plan(Pool pool, MixParams params, int variant, String hookText, AudioIntent intent,
              Map<Long, List<MaterialSegment>> semanticOverride) {
        MixParams p = params.normalized();
        long seed = (p.getSeed() == null ? System.nanoTime() : p.getSeed()) + variant * 7919L;
        Random rnd = new Random(seed);

        Plan plan = new Plan();
        plan.setSeed(seed);
        plan.setHookText(hookText);
        plan.setMinSec(p.getMinSec());
        plan.setMarketingStructure(p.getMarketingStructure());
        plan.setRequiresExternalAudio("material-audio".equalsIgnoreCase(p.getAudioMode()));
        seedAudioRotation(plan, pool, variant);
        applyRecentAudioUsage(plan, p.getRecentAudioUsage());

        if (!pool.hasVisual()) {
            structuralAudit(plan, pool, p);
            plan.getNotes().add("素材库里没有可用的画面素材（视频/图片），无法排版");
            return plan;
        }

        // ---------- 0. 结构角色审计 ----------
        structuralAudit(plan, pool, p);

        // ---------- 0.1 语义候选 vs 网格回退统计（解释性 + 降级审计） ----------
        Map<Long, List<MaterialSegment>> semantic = semanticOverride != null
                ? semanticOverride : loadSemanticSegments(pool);

        // ---------- 1. 目标时长 ----------
        double target = decideTarget(p, rnd);
        plan.setTargetSec(target);
        plan.setMarketingStages(MarketingStructurePlanner.stages(p.getMarketingStructure(), target));
        plan.setMarketingStructurePreview(MarketingStructurePlanner.preview(plan.getMarketingStages()));
        plan.getNotes().add("营销结构 " + p.getMarketingStructure() + "：" + plan.getMarketingStructurePreview());
        double sourceCapacity = sourceCapacity(pool);
        if (sourceCapacity < p.getMinSec()) {
            plan.getNotes().add(String.format(Locale.ROOT,
                    "素材不足，当前原始画面约 %.1fs，低于最低目标 %ds；请补充可读素材或降低目标时长",
                    sourceCapacity, p.getMinSec()));
            return plan;
        }

        // ---------- 2. 预算分配 ----------
        Material intro = selectIntro(pool.allVisual(), p, variant, plan);
        double introSec = intro == null ? 0 : p.getIntroDurationSec();
        List<Material> productVisuals = pool.product.isEmpty()
                ? (pool.body.isEmpty() ? pool.allVisual() : pool.body) : pool.product;
        boolean usingDedicatedEndcard = !pool.endcard.isEmpty();
        List<Material> endcardVisuals = usingDedicatedEndcard
                ? pool.endcard
                : (!pool.product.isEmpty() ? pool.product : (pool.body.isEmpty() ? pool.allVisual() : pool.body));
        int productSlots = productVisuals.isEmpty() ? 0 : p.getProductSlots();
        double productTotal = productSlots * p.getProductSec();
        double hookSec = (pool.hook.isEmpty() && pool.body.isEmpty()) ? 0 : p.getHookSec();
        Material hookVisual = hookSec > 0 ? pick(pool.hook.isEmpty() ? pool.body : pool.hook, rnd) : null;
        double endSec = (p.getEndcard() && !endcardVisuals.isEmpty()) ? p.getEndcardSec() : 0;
        double bodyBudget = Math.max(p.getSliceSec(), target - introSec - hookSec - productTotal - endSec);

        List<Cursor> bodyCur = buildCursors(pool.body.isEmpty() ? pool.allVisual() : pool.body, "body", p, variant, semantic);
        List<Cursor> celebCur = buildCursors(pool.celebrity, "celebrity", p, variant, semantic);
        if (bodyCur.isEmpty() && celebCur.isEmpty()) {
            plan.getNotes().add("切片池为空，请检查素材时长（每条至少 1 秒）");
            return plan;
        }

        // 素材越少，越需要多榨几轮才能凑够时长；但榨太多轮画面就开始像了，12 轮封顶
        int firstRoundCapacity = totalUsable(bodyCur, celebCur, p);
        int maxRounds = Math.max(2, Math.min(12,
                (int) Math.ceil(bodyBudget / Math.max(1.0, firstRoundCapacity * p.getSliceSec())) + 1));

        List<Segment> body = new ArrayList<>();

        // ---------- 3. 主体：body / celebrity 按比例均匀交错 ----------
        double bodyAcc = 0, celebUsed = 0;
        double ratio = pool.celebrity.isEmpty() ? 0 : p.getCelebrityRatio();
        Long lastMid = hookVisual == null ? null : hookVisual.getId();
        double lastDuration = -1;
        int guard = 0;

        while (bodyAcc < bodyBudget - 0.3 && guard++ < 600) {
            // 谁欠得多取谁 —— 天然把明星段均匀铺开，而不是开头连着放三段
            boolean wantCeleb = !celebCur.isEmpty() && celebUsed < ratio * bodyAcc;

            Segment s = wantCeleb ? takeNext(celebCur, lastMid, p, rnd, maxRounds, lastDuration) : null;
            if (s == null) s = takeNext(bodyCur, lastMid, p, rnd, maxRounds, lastDuration);
            if (s == null && !wantCeleb) s = takeNext(celebCur, lastMid, p, rnd, maxRounds, lastDuration);
            if (s == null) {
                // 所有素材都榨到上限了，放宽轮次上限再试一次
                maxRounds += 4;
                s = takeNext(bodyCur, lastMid, p, rnd, maxRounds, lastDuration);
                if (s == null) s = takeNext(celebCur, lastMid, p, rnd, maxRounds, lastDuration);
                if (s == null) break;
            }

            double remain = bodyBudget - bodyAcc;
            if (s.getDuration() > remain + 0.8) {
                s.setDuration(round(Math.max(1.0, remain)));
            }
            body.add(s);
            bodyAcc += s.getDuration();
            if ("celebrity".equals(s.getSlot())) celebUsed += s.getDuration();
            lastMid = s.getMaterialId();
            lastDuration = s.getDuration();
            if (body.size() > 220) break;
        }

        if (body.isEmpty()) {
            plan.getNotes().add("无法生成主体片段，请检查素材");
            return plan;
        }

        // ---------- 4. 产品段均匀插入主体 ----------
        //  从后往前插，避免前面插入导致后面的位置漂移
        if (productSlots > 0) {
            List<Integer> positions = new ArrayList<>();
            for (int i = 1; i <= productSlots; i++) {
                positions.add((int) Math.round(body.size() * (i / (double) (productSlots + 1))));
            }
            for (int i = productSlots - 1; i >= 0; i--) {
                int pos = Math.max(0, Math.min(body.size(), positions.get(i)));
                Long before = pos > 0 ? body.get(pos - 1).getMaterialId() : null;
                Long after = pos < body.size() ? body.get(pos).getMaterialId() : null;
                Material pm = pickAvoiding(productVisuals, before, after, rnd);
                Segment ps = cut(pm, offsetFor(pm, p.getProductSec(), variant + i, rnd, false),
                        p.getProductSec(), "product");
                body.add(pos, ps);
            }
        }

        // ---------- 5. 拼上钩子与片尾 ----------
        List<Segment> timeline = new ArrayList<>();
        double acc = 0;

        if (intro != null) {
            Segment s = cut(intro, offsetFor(intro, introSec, variant, rnd, true), introSec, "intro");
            timeline.add(s);
            acc += s.getDuration();
            plan.getNotes().add(("rotate".equalsIgnoreCase(p.getIntroMode()) ? "片头按批次轮换：" : "使用固定片头：") + intro.getName());
        }
        if (hookSec > 0) {
            if (hookVisual != null) {
                Segment s = cut(hookVisual, offsetFor(hookVisual, hookSec, variant, rnd, true), hookSec, "hook");
                timeline.add(s);
                acc += s.getDuration();
            }
        }
        for (Segment s : body) {
            timeline.add(s);
            acc += s.getDuration();
        }
        if (endSec > 0) {
            Long previous = timeline.isEmpty() ? null : timeline.get(timeline.size() - 1).getMaterialId();
            Material em = pickAvoiding(endcardVisuals, previous, null, rnd);
            if (em != null) {
                Segment s = cut(em, offsetFor(em, endSec, variant, rnd, false), endSec, "endcard");
                timeline.add(s);
                acc += s.getDuration();
            }
        }

        // ---------- 6. 硬约束校正：必须落在 [minSec, maxSec] ----------
        acc = trimToRange(timeline, p, acc, plan, bodyCur, celebCur, rnd, maxRounds);

        // ---------- 7. 自检 ----------
        List<String> issues = validate(timeline);
        plan.getNotes().addAll(issues);
        applyInternalDedupeGate(plan, timeline, p);

        double timelineOffset = 0;
        for (int i = 0; i < timeline.size(); i++) {
            Segment segment = timeline.get(i);
            segment.setIndex(i);
            if ("hook".equals(segment.getSlot())) {
                plan.setHookStartSec(round(timelineOffset));
                plan.setHookEndSec(round(timelineOffset + segment.getDuration()));
            }
            timelineOffset += segment.getDuration();
        }
        plan.setSegments(timeline);
        applySemanticAudit(plan, timeline, semantic);
        plan.setPlannedSec(round(acc));
        if (plan.getPlannedSec() >= p.getMinSec() && plan.getPlannedSec() < target) {
            plan.getNotes().add(String.format(Locale.ROOT,
                    "当前计划 %.1fs 已达到交付下限 %ds，但低于推荐目标 %.1fs；补充画面素材可增加时长",
                    plan.getPlannedSec(), p.getMinSec(), target));
        }

        // ---------- 7.5 钩子策略 + 中段再钩子时间窗 + 分层去重风险 ----------
        String strategy = p.getHookStrategy();
        if (strategy == null || strategy.isBlank()) {
            strategy = HookStrategy.select(null, variant).name();
        }
        plan.setHookStrategy(strategy);

        String rehook = p.getRehookText();
        if (rehook != null && !rehook.isBlank() && plan.getPlannedSec() > 0) {
            double rs = Math.max(plan.getHookEndSec(), round(plan.getPlannedSec() * 0.45));
            double rw = Math.min(3.0, Math.max(2.0, p.getHookSec() == null ? 3.0 : p.getHookSec()));
            double re = Math.min(plan.getPlannedSec(), rs + rw);
            if (re - rs >= 1.0) {
                plan.setRehookText(rehook);
                plan.setRehookWindowStart(round(rs));
                plan.setRehookWindowEnd(round(re));
                plan.getNotes().add("已安排中段再钩子（策略 " + strategy + "）："
                        + round(rs) + "–" + round(re) + "s · " + rehook);
            } else {
                plan.getNotes().add("片长过短，无法安排中段再钩子窗口");
            }
        }

        int sourceOverlap = countSameSourceOverlap(timeline);
        if ("strict".equalsIgnoreCase(p.getDedupStrictness()) && sourceOverlap > 0) {
            plan.getNotes().add("严格去重无法完全满足：仍有 " + sourceOverlap + " 处同源时间重叠，仅 "
                    + distinctSources(timeline) + " 个不同画面源；建议补充素材或放宽去重级别");
        }

        // ---------- 8. 音轨（支持最少使用轮换）----------
        List<Material> voiceCandidates = selectableVoices(pool.voice, p.getVoiceMaterialId());
        List<MixPlanner.Plan.VoiceSegment> voiceSegments = planVoiceSegments(voiceCandidates, plan.getPlannedSec(), p.getVoiceMaterialId(), plan);
        plan.setVoiceSegments(voiceSegments);
        Material voice = voiceSegments.isEmpty() ? null : voiceCandidates.stream()
                .filter(candidate -> Objects.equals(candidate.getId(), voiceSegments.get(0).getMaterialId()))
                .findFirst().orElse(null);
        if (voice != null) {
            plan.setVoiceMaterialId(voice.getId());
            plan.setVoicePath(voice.getFilePath());
            plan.setVoiceDurationSec(voiceSegments.stream().mapToDouble(MixPlanner.Plan.VoiceSegment::getDuration).sum());
            for (MixPlanner.Plan.VoiceSegment segment : voiceSegments) plan.audioUsage.merge("voice:" + segment.getMaterialId(), 1, Integer::sum);
            if (voiceSegments.size() > 1) plan.getNotes().add("口播覆盖不足，已按时间线接入 " + voiceSegments.size() + " 段不同口播素材，禁止单段循环");
            else if (p.getVoiceMaterialId() == null) plan.getNotes().add("已按近期使用记录轮换口播：" + voice.getName());
        }
        Material bgm = pickExplicitAudio(pool, p.getBgmMaterialId());
        boolean bgmByIntent = false;
        if (bgm == null) {
            bgm = pickIntentBgm(pool.bgm, intent, plan.audioUsage);
            bgmByIntent = bgm != null;
        }
        if (bgm == null) bgm = pickLeastUsed(pool.bgm, null, plan.audioUsage, "bgm");
        if (bgm != null && voice != null && sameAudioSource(voice, bgm)) {
            if (p.getBgmMaterialId() != null && p.getVoiceMaterialId() == null) {
                plan.getNotes().add("已避免同一文件同时作为口播和 BGM，保留显式 BGM：" + bgm.getName());
                voice = null;
                plan.setVoiceMaterialId(null);
                plan.setVoicePath(null);
                plan.setVoiceDurationSec(0);
                plan.audioUsage.remove("voice:" + bgm.getId());
            } else {
                plan.getNotes().add("已阻止同一文件同时作为口播和 BGM，保留口播单层音轨：" + voice.getName());
                bgm = null;
            }
        }
        if (bgm != null) {
            plan.setBgmMaterialId(bgm.getId());
            plan.setBgmPath(bgm.getFilePath());
            plan.setBgmDurationSec(mediaDuration(bgm));
            plan.audioUsage.merge("bgm:" + bgm.getId(), 1, Integer::sum);
            if (p.getBgmMaterialId() != null && !MaterialRole.bgm.equals(bgm.getRole())) {
                plan.getNotes().add("已按你的选择将音频作为循环背景声：" + bgm.getName()
                        + "（当前角色为 " + bgm.getRole() + "，无需重新导入）");
            }
            if (bgmByIntent) {
                plan.getNotes().add("已按项目语义偏好选择 BGM：" + bgm.getName());
            } else if (p.getBgmMaterialId() == null) {
                plan.getNotes().add("已按近期使用记录轮换 BGM：" + bgm.getName());
            }
        } else if (p.getBgmMaterialId() != null) {
            plan.getNotes().add("所选背景音乐不可用：请确认该素材仍可读取且类型为音频");
        }
        // 人声优先：有口播/配音时，BGM 自动压低（ducking），保持人声为权威音轨。
        if (voice != null && bgm != null && intent != null && intent.isDuckBgm()) {
            plan.setDuckBgm(true);
            plan.getNotes().add("已优先保留人声并压低背景音乐（ducking）");
        }
        // A hook voice is opt-in only. Never auto-select a second human/AI narration track.
        Material hookAudio = pickExplicitAudio(pool, p.getHookAudioMaterialId());
        if (p.getHookAudioMaterialId() != null && hookAudio == null) {
            plan.getNotes().add("所选钩子音频不可用：请确认该素材仍可读取且类型为音频");
        } else if (hookAudio == null && Boolean.TRUE.equals(p.getAutoMatchAudio())) {
            plan.getNotes().add("未指定独立钩子音频，已保留钩子画面与字幕，不自动叠加第二条人声");
        }
        if (hookAudio != null && voice != null && sameAudioSource(hookAudio, voice)) {
            plan.getNotes().add("已阻止钩子音频与主口播重复叠加，保留主口播和钩子字幕");
            hookAudio = null;
        }
        if (hookAudio != null && plan.getHookEndSec() > plan.getHookStartSec()) {
            plan.setHookAudioMaterialId(hookAudio.getId());
            plan.setHookAudioPath(hookAudio.getFilePath());
            plan.usedHookVoices.add("hookVoice:" + hookAudio.getId());
            plan.getNotes().add("钩子声画已绑定：" + hookAudio.getName() + " · "
                    + round(plan.getHookStartSec()) + "–" + round(plan.getHookEndSec()) + "s");
        }
        if (voice == null && bgm == null && hookAudio == null) {
            if (plan.isRequiresExternalAudio()) {
                plan.getNotes().add("没有可用 BGM、口播或钩子音频；默认素材音轨模式会产生静音成片，需至少导入一条 BGM或切换音频模式");
            } else {
                plan.getNotes().add("未选择独立音轨，将按当前音频模式处理原片声音或 AI 口播");
            }
        }
        return plan;
    }

    /** One strictly bounded material folder stage for ordered production workflows. */
    public record OrderedFolderStep(int order, String name, List<Material> materials, double targetSec, boolean required) {
    }

    /**
     * Produces a timeline in the exact order supplied by the human-configured folder steps.
     * Every step receives only its own candidates; this method never falls back to another step
     * or to the global material library.
     */
    public Plan planOrderedFolders(List<OrderedFolderStep> steps, MixParams params, int variant,
                                   String hookText, AudioIntent intent) {
        MixParams p = params.normalized();
        long seed = (p.getSeed() == null ? System.nanoTime() : p.getSeed()) + variant * 7919L;
        Random rnd = new Random(seed);
        Plan plan = new Plan();
        plan.setSeed(seed);
        plan.setHookText(hookText);
        plan.setMinSec(p.getMinSec());
        plan.setMarketingStructure(p.getMarketingStructure());
        plan.setRequiresExternalAudio("material-audio".equalsIgnoreCase(p.getAudioMode()));
        if (steps == null || steps.isEmpty()) {
            plan.getNotes().add("严格文件夹顺序未配置读取步骤，已拒绝回退到全库素材");
            return plan;
        }
        double target = decideTarget(p, rnd);
        plan.setTargetSec(target);
        plan.setMarketingStages(MarketingStructurePlanner.stages(p.getMarketingStructure(), target));
        plan.setMarketingStructurePreview(MarketingStructurePlanner.preview(plan.getMarketingStages()));
        plan.getNotes().add("营销结构 " + p.getMarketingStructure() + "：" + plan.getMarketingStructurePreview());
        List<OrderedFolderStep> enabled = steps.stream().filter(Objects::nonNull).toList();
        if (enabled.isEmpty()) {
            plan.getNotes().add("严格文件夹顺序没有启用的读取步骤");
            return plan;
        }
        List<Segment> timeline = new ArrayList<>();
        List<Material> orderedMaterials = new ArrayList<>();
        Long previous = null;
        double acc = 0;
        for (int i = 0; i < enabled.size(); i++) {
            OrderedFolderStep step = enabled.get(i);
            List<Material> visuals = step.materials() == null ? List.of() : step.materials().stream()
                    .filter(material -> material.getFileType() != Material.FileType.audio)
                    .toList();
            if (visuals.isEmpty()) {
                String message = "第 " + step.order() + " 步「" + step.name() + "」没有可读画面素材";
                if (step.required()) {
                    plan.getNotes().add(message + "，已阻断严格顺序计划；不会跨步骤补位。请补充该步骤素材或调整为非必需步骤");
                    plan.setSegments(List.of());
                    plan.setPlannedSec(0.0);
                    return plan;
                }
                plan.getNotes().add(message + "，已跳过非必需步骤；不会跨步骤补位");
                continue;
            }
            orderedMaterials.addAll(step.materials());
            double remainingTarget = Math.max(p.getSliceSec(), target - acc);
            double requested = step.targetSec() > 0 ? step.targetSec() : remainingTarget / Math.max(1, enabled.size() - i);
            requested = Math.min(remainingTarget, Math.max(0.8, requested));
            boolean introStep = Boolean.TRUE.equals(p.getIntroEnabled()) && step.order() == 1;
            // 非片头段仍按八段式角色分配（第 1 步 = hook），片头只占独立的 intro 槽位。
            String slot = orderedSlot(step.order());
            double stepAcc = 0;
            if (introStep) {
                // 与主计划共用同一套片头选择契约：fixed 固定 / rotate 按批次轮换、不重复保护、
                // 「明确允许不足时重复」都由 selectIntro 裁决。候选不足时只跳过片头（附审计说明），
                // 绝不跨步骤补位，也不把片头时长叠加到步骤目标之外（片头时长计入 stepAcc，
                // 后续正文片段只补足到 requested，不会重复计时）。
                Material intro = selectIntro(visuals, p, variant, plan);
                if (intro != null) {
                    double introSec = Math.min(requested, p.getIntroDurationSec());
                    Segment introSegment = cut(intro, offsetFor(intro, introSec, variant, rnd, true), introSec, "intro");
                    timeline.add(introSegment);
                    stepAcc = introSegment.getDuration();
                    acc += stepAcc;
                    previous = intro.getId();
                    plan.getNotes().add(("rotate".equalsIgnoreCase(p.getIntroMode()) ? "第 1 步片头按批次轮换：" : "使用固定片头：") + intro.getName());
                }
            }
            List<Cursor> cursors = buildCursors(visuals, slot, p, variant + step.order(), loadSemanticSegments(buildPool(visuals)));
            if (cursors.isEmpty()) {
                String message = "第 " + step.order() + " 步「" + step.name() + "」没有满足切片条件的素材";
                if (step.required()) {
                    plan.getNotes().add(message + "，已阻断严格顺序计划；不会跨目录补齐。请补充该步骤素材或调整为非必需步骤");
                    plan.setSegments(List.of());
                    plan.setPlannedSec(0.0);
                    return plan;
                }
                plan.getNotes().add(message + "，已跳过非必需步骤；不会跨目录补齐");
                continue;
            }
            int guard = 0;
            int maxRounds = Math.max(2, Math.min(12, (int) Math.ceil(requested / Math.max(0.8, p.getSliceSec())) + 2));
            while (stepAcc < requested - 0.15 && guard++ < 300) {
                Segment segment = takeNext(cursors, previous, p, rnd, maxRounds, -1);
                if (segment == null) break;
                double remaining = requested - stepAcc;
                if (segment.getDuration() > remaining + 0.25) segment.setDuration(round(Math.max(0.8, remaining)));
                timeline.add(segment);
                stepAcc += segment.getDuration();
                acc += segment.getDuration();
                previous = segment.getMaterialId();
                if (timeline.size() > 240) break;
            }
            if (stepAcc + 0.15 < requested) {
                String message = "第 " + step.order() + " 步「" + step.name() + "」仅生成 "
                        + round(stepAcc) + "s，目标 " + round(requested) + "s";
                if (step.required()) {
                    plan.getNotes().add(message + "；不会跨目录补齐，严格顺序计划不可交付");
                    plan.setSegments(List.of());
                    plan.setPlannedSec(0.0);
                    return plan;
                }
                plan.getNotes().add(message + "；不会跨目录补齐");
            } else {
                plan.getNotes().add("第 " + step.order() + " 步「" + step.name() + "」已锁定 " + round(stepAcc) + "s");
            }
        }
        if (timeline.isEmpty()) {
            plan.getNotes().add("严格文件夹顺序未生成任何画面片段");
            return plan;
        }
        if (acc + 0.15 < p.getMinSec()) {
            plan.getNotes().add("严格步骤总时长不足 " + round(acc) + "s；不会跨目录补位，请补充对应步骤素材或调整目标时长");
        }
        for (int i = 0; i < timeline.size(); i++) {
            Segment segment = timeline.get(i);
            segment.setIndex(i);
        }
        // 钩子时间窗：定位第一个 hook 角色片段；启用了片头时它自然从片头结束处起算，
        // 钩子音频绑定会与这个窗口对齐。找不到 hook 段时回退到时间线起点。
        double timelineOffset = 0;
        boolean hookWindowFound = false;
        for (Segment segment : timeline) {
            if ("hook".equals(segment.getSlot())) {
                plan.setHookStartSec(round(timelineOffset));
                plan.setHookEndSec(round(timelineOffset + segment.getDuration()));
                hookWindowFound = true;
                break;
            }
            timelineOffset += segment.getDuration();
        }
        if (!hookWindowFound && !timeline.isEmpty()) {
            plan.setHookStartSec(0);
            plan.setHookEndSec(round(timeline.get(0).getDuration()));
        }
        plan.setSegments(timeline);
        plan.setPlannedSec(round(acc));
        plan.setHookStrategy(p.getHookStrategy() == null || p.getHookStrategy().isBlank()
                ? HookStrategy.select(null, variant).name() : p.getHookStrategy());
        Pool audioPool = buildPool(orderedMaterials);
        seedAudioRotation(plan, audioPool, variant);
        applyRecentAudioUsage(plan, p.getRecentAudioUsage());
        applyOrderedAudio(plan, audioPool, p, intent);
        plan.getNotes().addAll(validate(timeline));
        applyInternalDedupeGate(plan, timeline, p);
        if (plan.getPlannedSec() < p.getMinSec()) {
            plan.getNotes().add("严格步骤总时长 " + round(plan.getPlannedSec()) + "s 低于最低交付时长 " + p.getMinSec() + "s");
        }
        return plan;
    }

    private String orderedSlot(int order) {
        return switch (order) {
            case 1 -> "hook";
            case 5, 6 -> "product";
            case 8 -> "endcard";
            default -> "body";
        };
    }

    private void applyOrderedAudio(Plan plan, Pool pool, MixParams p, AudioIntent intent) {
        Material voice = pickExplicitVoice(selectableVoices(pool.voice, p.getVoiceMaterialId()), p.getVoiceMaterialId(), plan, "voice");
        if (voice != null) {
            plan.setVoiceMaterialId(voice.getId());
            plan.setVoicePath(voice.getFilePath());
            plan.setVoiceDurationSec(mediaDuration(voice));
        }
        Material bgm = pickExplicitAudio(pool, p.getBgmMaterialId());
        if (bgm == null) bgm = pickIntentBgm(pool.bgm, intent, plan.audioUsage);
        if (bgm == null) bgm = pickLeastUsed(pool.bgm, null, plan.audioUsage, "bgm");
        if (bgm != null && (voice == null || !sameAudioSource(voice, bgm))) {
            plan.setBgmMaterialId(bgm.getId());
            plan.setBgmPath(bgm.getFilePath());
            plan.setBgmDurationSec(mediaDuration(bgm));
        }
        // 钩子音频与「片头之后的钩子时间窗」对齐绑定（与主计划同规则：仅显式指定、绝不自动叠加第二条人声）。
        Material hookAudio = pickExplicitAudio(pool, p.getHookAudioMaterialId());
        if (p.getHookAudioMaterialId() != null && hookAudio == null) {
            plan.getNotes().add("所选钩子音频不可用：请确认该素材仍可读取且类型为音频");
        } else if (hookAudio == null && Boolean.TRUE.equals(p.getAutoMatchAudio())) {
            plan.getNotes().add("未指定独立钩子音频，已保留钩子画面与字幕，不自动叠加第二条人声");
        }
        if (hookAudio != null && voice != null && sameAudioSource(hookAudio, voice)) {
            plan.getNotes().add("已阻止钩子音频与主口播重复叠加，保留主口播和钩子字幕");
            hookAudio = null;
        }
        if (hookAudio != null && plan.getHookEndSec() > plan.getHookStartSec()) {
            plan.setHookAudioMaterialId(hookAudio.getId());
            plan.setHookAudioPath(hookAudio.getFilePath());
            plan.usedHookVoices.add("hookVoice:" + hookAudio.getId());
            plan.getNotes().add("钩子声画已绑定：" + hookAudio.getName() + " · "
                    + round(plan.getHookStartSec()) + "–" + round(plan.getHookEndSec()) + "s");
        }
        if (voice != null && bgm != null && intent != null && intent.isDuckBgm()) plan.setDuckBgm(true);
        if (plan.isRequiresExternalAudio() && plan.getVoicePath() == null && plan.getBgmPath() == null) {
            plan.getNotes().add("严格目录步骤内没有可用口播或 BGM；不会从其他文件夹自动补音频");
        }
    }

    // ---------------- 内部 ----------------

    /** Audit which requested structural roles are empty in the pool and add notes to the plan. */
    private void structuralAudit(Plan plan, Pool pool, MixParams p) {
        if (p.getHookSec() > 0 && pool.hook.isEmpty() && pool.body.isEmpty()) {
            plan.getNotes().add("缺少钩子素材 (hook 角色为空)：开头吸引力不足，成品将缺少前几秒抓人内容");
        }
        if (!pool.hook.isEmpty() && pool.hook.size() <= 1) {
            plan.getNotes().add("钩子素材仅 " + pool.hook.size() + " 条，建议补充多样化的开头钩子");
        }
        if (p.getProductSlots() > 0 && pool.product.isEmpty()) {
            plan.getNotes().add("未标注产品角色：产品段将从项目相关主体画面中补位；建议给产品近景打上 product 标签以提高转化画面准确度");
        }
        if (Boolean.TRUE.equals(p.getEndcard()) && pool.endcard.isEmpty()) {
            plan.getNotes().add("未标注片尾卡：片尾将复用项目相关主体画面承载行动引导；建议补充 endcard 素材以提高转化完整度");
        }
        if (p.getCelebrityRatio() > 0 && pool.celebrity.isEmpty()) {
            plan.getNotes().add("未标注达人角色：已将达人占比自动回收到主体画面，不会阻断成片");
        }
        if (!pool.hasVisual()) {
            plan.getNotes().add("素材库中没有任何可用画面素材（视频/图片），无法生成可交付成片");
        }
    }

    /** Raw visual capacity used as a hard lower-bound check before variant slicing.
     * Images contribute one conservative default frame; video contributes its readable duration.
     */
    private double sourceCapacity(Pool pool) {
        double total = 0;
        for (Material material : pool.allVisual()) {
            if (material.getFileType() == Material.FileType.image) total += 3.0;
            else total += Math.max(0, material.getDurationSec() == null ? 0 : material.getDurationSec());
        }
        return total;
    }

    /**
     * 首轮可用切片数：每条视频严格按 floor(duration / sliceSec) 计数，并受单素材切片上限
     * 约束；图片最多贡献一片。这样容量估计与 Cursor 的网格切片语义一致，不会把素材总秒数
     * 误当作切片数量，也不会忽略 maxSlicesPerMaterial。
     */
    private int totalUsable(List<Cursor> a, List<Cursor> b, MixParams p) {
        int cap = Boolean.TRUE.equals(p.getExplodeLongClips())
                ? Math.max(1, p.getMaxSlicesPerMaterial()) : 1;
        int count = 0;
        for (Cursor cursor : a) count += usableSlices(cursor, p.getSliceSec(), cap);
        for (Cursor cursor : b) count += usableSlices(cursor, p.getSliceSec(), cap);
        return count;
    }

    private int usableSlices(Cursor cursor, double sliceSec, int cap) {
        if (cursor.m.getFileType() == Material.FileType.image) return 1;
        if (!cursor.semantic.isEmpty()) {
            return Math.min(cap, Math.max(0, cursor.semantic.size()));
        }
        int available = (int) Math.floor(cursor.dur / Math.max(0.5, sliceSec));
        return Math.min(cap, Math.max(0, available));
    }

    /** 计划自检：把问题提前暴露给人，而不是等渲染完看片才发现 */
    private List<String> validate(List<Segment> tl) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int dup = 0, adj = 0, oob = 0;
        Long prev = null;
        for (Segment s : tl) {
            String k = s.getMaterialId() + "@" + s.getSourceStart() + "+" + s.getDuration();
            if (!seen.add(k)) dup++;
            if (prev != null && prev.equals(s.getMaterialId())) adj++;
            if (s.getSourceDuration() > 0 && s.getSourceStart() + s.getDuration() > s.getSourceDuration() + 0.05) oob++;
            prev = s.getMaterialId();
        }
        if (dup > 0) out.add("⚠ 有 " + dup + " 段内容完全重复，建议补充素材或调小切片时长");
        if (adj > 0) out.add("提示：有 " + adj + " 处相邻片段来自同一素材（素材种类偏少）");
        if (oob > 0) out.add("⚠ 有 " + oob + " 段起点越界，已自动收敛");
        int sameSourceOverlap = countSameSourceOverlap(tl);
        if (sameSourceOverlap > 0) out.add("⚠ 有 " + sameSourceOverlap + " 处同源片段时间重叠，建议开启严格去重或补充素材");
        return out;
    }

    /** Reject duplicate footage before FFmpeg starts; `off` remains an explicit opt-out. */
    private void applyInternalDedupeGate(Plan plan, List<Segment> timeline, MixParams params) {
        if ("off".equalsIgnoreCase(params.getDedupStrictness())) return;
        Set<String> unique = new HashSet<>();
        int duplicates = 0;
        for (Segment segment : timeline) {
            String key = segment.getMaterialId() + "@" + round(segment.getSourceStart()) + "+" + round(segment.getDuration());
            if (!unique.add(key)) duplicates++;
        }
        int overlaps = countSameSourceOverlap(timeline);
        boolean strict = "strict".equalsIgnoreCase(params.getDedupStrictness());
        if (duplicates > 0 || (strict && overlaps > 0)) {
            plan.setInternallyUnique(false);
            plan.getNotes().add("当前素材变体含 " + duplicates + " 段完全重复"
                    + (strict && overlaps > 0 ? "，以及 " + overlaps + " 处同源重叠" : "")
                    + "；已在渲染前拒绝并尝试切换素材变体");
        }
    }

    /** 分层去重：统计同一素材源内 [start,end) 时间区间重叠的段数。 */
    private int countSameSourceOverlap(List<Segment> tl) {
        Map<Long, List<double[]>> byMaterial = new HashMap<>();
        int overlap = 0;
        for (Segment s : tl) {
            if (s.getSourceDuration() <= 0) continue; // 图片无源区间，跳过
            List<double[]> intervals = byMaterial.computeIfAbsent(s.getMaterialId(), k -> new ArrayList<>());
            double start = s.getSourceStart();
            double end = start + s.getDuration();
            for (double[] iv : intervals) {
                if (start < iv[1] && end > iv[0]) {
                    overlap++;
                    break;
                }
            }
            intervals.add(new double[]{start, end});
        }
        return overlap;
    }

    /** 时间线上不同画面源的数量，用于严格去重不足报告。 */
    private int distinctSources(List<Segment> tl) {
        Set<Long> sources = new HashSet<>();
        for (Segment s : tl) if (s.getMaterialId() != null) sources.add(s.getMaterialId());
        return sources.size();
    }

    /** Audit actual timeline slices, not candidate material count. */
    private void applySemanticAudit(Plan plan, List<Segment> timeline, Map<Long, List<MaterialSegment>> semantic) {
        int sceneSegments = 0;
        int gridSegments = 0;
        Set<Long> fallbackMaterials = new LinkedHashSet<>();
        for (Segment timelineSegment : timeline) {
            if (timelineSegment == null || timelineSegment.getSourceDuration() <= 0) continue;
            boolean scene = semantic != null && semantic.getOrDefault(timelineSegment.getMaterialId(), List.of()).stream()
                    .anyMatch(candidate -> overlaps(timelineSegment, candidate));
            if (scene) sceneSegments++;
            else {
                gridSegments++;
                fallbackMaterials.add(timelineSegment.getMaterialId());
            }
        }
        plan.setSemanticSegmentCount(sceneSegments);
        plan.setGridFallbackCount(gridSegments);
        plan.setSemanticAnalysisAvailable(semantic != null && semantic.values().stream().anyMatch(list -> list != null && !list.isEmpty()));
        plan.setSemanticAuditApplied(true);
        List<String> reasons = new ArrayList<>();
        for (Long materialId : fallbackMaterials) reasons.add("material " + materialId + ": 未使用 scene 片段，保留网格回退");
        plan.setFallbackReasons(reasons);
        if (sceneSegments > 0) plan.getNotes().add("实际时间线使用 " + sceneSegments + " 个 scene 语义候选镜头");
        if (gridSegments > 0) plan.getNotes().add(gridSegments + " 个时间线镜头已回退网格切片（降级）");
    }

    private boolean overlaps(Segment timelineSegment, MaterialSegment candidate) {
        if (candidate == null || candidate.getStartSec() == null || candidate.getEndSec() == null) return false;
        double start = timelineSegment.getSourceStart();
        double end = start + timelineSegment.getDuration();
        return start < candidate.getEndSec() && end > candidate.getStartSec();
    }

    /** 惰性加载 + 缓存结构化镜头片段；仅采纳已完成且来源为 scene 的分析，避免把均匀切片兜底误当语义候选。 */
    private Map<Long, List<MaterialSegment>> loadSemanticSegments(Pool pool) {
        if (segmentStore == null || analysisStore == null) return Map.of();
        Map<Long, List<MaterialSegment>> result = new HashMap<>();
        for (Material m : pool.allVisual()) {
            if (m.getFileType() == Material.FileType.image) continue;
            List<MaterialSegment> segments = semanticFor(m.getId());
            if (!segments.isEmpty()) result.put(m.getId(), segments);
        }
        return result;
    }

    private List<MaterialSegment> semanticFor(Long materialId) {
        SemanticCacheEntry entry = semanticCache.get(materialId);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.at < SEMANTIC_CACHE_TTL_MS) return entry.segments;
        List<MaterialSegment> segments = List.of();
        try {
            var analysis = analysisStore.findByMaterialId(materialId).orElse(null);
            if (analysis != null && "completed".equals(analysis.getStatus())
                    && "scene".equals(analysis.getSource())) {
                List<MaterialSegment> loaded = segmentStore.findByMaterialId(materialId);
                segments = loaded == null ? List.of() : loaded;
            }
        } catch (Exception e) {
            log.debug("semantic segment load failed for material {}: {}", materialId, e.toString());
        }
        semanticCache.put(materialId, new SemanticCacheEntry(segments, now));
        return segments;
    }

    /** 目标时长：dense 时向 100s 收敛，否则区间内随机 */
    private double decideTarget(MixParams p, Random rnd) {
        if (p.getTargetSec() != null) return p.getTargetSec();
        int min = p.getMinSec(), max = p.getMaxSec();
        if (p.getDense()) {
            int center = Math.min(max, Math.max(min, 100));
            int spread = Math.min(20, (max - min) / 2);
            int v = center + rnd.nextInt(spread * 2 + 1) - spread;
            return Math.min(max, Math.max(min, v));
        }
        return min + rnd.nextInt(Math.max(1, max - min + 1));
    }

    private double jitter(MixParams p, Random rnd) {
        return smoothJitter(p, rnd, -1);
    }

    /**
     * Generate a segment duration with optional smoothing from the previous segment.
     * When lastDuration > 0, the new duration is constrained to be within 50% of the previous
     * to avoid jarring visual rhythm changes between consecutive clips.
     */
    private double smoothJitter(MixParams p, Random rnd, double lastDuration) {
        if ("equal".equalsIgnoreCase(p.getDurationAllocationMode())) {
            return round(Math.max(p.getMinSegmentSec(), Math.min(p.getMaxSegmentSec(), p.getSliceSec())));
        }
        double min = p.getMinSegmentSec();
        double max = p.getMaxSegmentSec();
        double weighted = p.getSliceSec() + (rnd.nextDouble() * 2 - 1) * p.getSliceJitter();
        double random = min + rnd.nextDouble() * Math.max(0, max - min);
        double result = round(Math.max(min, Math.min(max, (weighted + random) / 2.0)));

        // Smooth against previous segment: prevent jumps larger than 50% of the midpoint
        if (lastDuration > 0) {
            double mid = (min + max) / 2.0;
            double maxChange = mid * 0.5;
            double lower = Math.max(min, lastDuration - maxChange);
            double upper = Math.min(max, lastDuration + maxChange);
            result = round(Math.max(lower, Math.min(upper, result)));
        }
        return result;
    }

    private Material selectIntro(List<Material> visuals, MixParams p, int variant, Plan plan) {
        if (!Boolean.TRUE.equals(p.getIntroEnabled())) return null;
        List<Material> candidates = visuals == null ? List.of() : visuals.stream()
                .filter(material -> canCoverIntro(material, p.getIntroDurationSec()))
                .sorted(Comparator.comparing(Material::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        if (candidates.isEmpty()) {
            plan.getNotes().add("自定义片头没有满足时长的已授权画面素材，已跳过片头");
            return null;
        }
        Material fixed = findById(candidates, p.getIntroMaterialId());
        if (!"rotate".equalsIgnoreCase(p.getIntroMode())) {
            if (fixed == null && p.getIntroMaterialId() != null) plan.getNotes().add("指定固定片头不在当前授权素材池或时长不足，已跳过片头");
            return fixed;
        }
        List<Material> ordered = new ArrayList<>(candidates);
        if (fixed != null) {
            ordered.remove(fixed);
            ordered.add(0, fixed);
        }
        if (ordered.size() < 2 && Boolean.TRUE.equals(p.getIntroNoRepeat())
                && !Boolean.TRUE.equals(p.getIntroAllowRepeatWhenInsufficient())) {
            plan.getNotes().add("片头轮换候选仅 " + ordered.size() + " 条，已启用不重复保护；请补充片头素材或明确允许不足时重复");
            return null;
        }
        if (ordered.size() < 2) plan.getNotes().add("片头轮换候选不足，已按用户允许在不足时重复");
        return ordered.get(Math.floorMod(variant, ordered.size()));
    }

    private boolean canCoverIntro(Material material, double requiredDuration) {
        if (material == null || material.getFilePath() == null) return false;
        if (material.getFileType() == Material.FileType.image) return true;
        return material.getDurationSec() != null && material.getDurationSec() + 0.05 >= requiredDuration;
    }

    private Material findById(List<Material> materials, Long id) {
        if (id == null || materials == null) return null;
        for (Material material : materials) if (id.equals(material.getId())) return material;
        return null;
    }

    private Material pickAvoiding(List<Material> materials, Long before, Long after, Random rnd) {
        List<Material> alternatives = materials.stream()
                .filter(material -> !Objects.equals(material.getId(), before) && !Objects.equals(material.getId(), after))
                .toList();
        return pick(alternatives.isEmpty() ? materials : alternatives, rnd);
    }

    private Segment base(Material m, String slot) {
        Segment s = new Segment();
        s.setMaterialId(m.getId());
        s.setMaterialName(m.getName());
        s.setFilePath(m.getFilePath());
        s.setKind(m.getFileType() == Material.FileType.image ? "image" : "video");
        s.setSlot(slot);
        return s;
    }

    /** 直接切一段（用于 hook / product / endcard 这类定点段），带越界收敛 */
    private Segment cut(Material m, double start, double dur, String slot) {
        double total = m.getDurationSec() == null ? 0 : m.getDurationSec();
        Segment s = base(m, slot);
        if (s.getKind().equals("image")) {
            s.setSourceStart(0);
            s.setDuration(round(dur));
            s.setSourceDuration(0);
            return s;
        }
        double d = Math.min(dur, Math.max(0.8, total - 0.08));
        double st = Math.max(0, Math.min(start, total - d - 0.08));
        s.setSourceStart(round(st));
        s.setDuration(round(d));
        s.setSourceDuration(round(total));
        return s;
    }

    private double offsetFor(Material m, double need, int variant, Random rnd, boolean preferHead) {
        double dur = m.getDurationSec() == null ? 0 : m.getDurationSec();
        if (dur <= need + 0.2) return 0;
        if (preferHead && variant == 0) return 0;
        double max = dur - need - 0.1;
        double b = (variant * need) % Math.max(0.1, max);
        return round(Math.min(max, b + rnd.nextDouble() * Math.min(2.0, max)));
    }

    /**
     * 时长硬约束：超了砍、不足补。
     * 补的时候从游标再取<b>新</b>片段，绝不复制已有片段。
     */
    private double trimToRange(List<Segment> tl, MixParams p, double acc, Plan plan,
                               List<Cursor> bodyCur, List<Cursor> celebCur, Random rnd, int maxRounds) {
        // 1) 删除阶段：优先从尾部删除非保护段（body）；全部是保护段时绝不尾部硬砍
        //    （旧逻辑 removeAt<0 时删最后一段，会误删 endcard/hook，破坏节奏），交给缩短阶段。
        int guard = 0;
        while (acc > p.getMaxSec() && tl.size() > 1 && guard++ < 500) {
            int removeAt = -1;
            for (int i = tl.size() - 1; i >= 0; i--) {
                String slot = tl.get(i).getSlot();
                if (!"product".equals(slot) && !"endcard".equals(slot) && !"hook".equals(slot)) {
                    removeAt = i;
                    break;
                }
            }
            if (removeAt < 0) break;
            acc -= tl.remove(removeAt).getDuration();
        }

        // 2) 缩短阶段：优先缩短时长最长的非保护段（分摊压缩、保留节奏）；无非保护段时
        //    才允许缩短最后一段保护段，且每段最多缩到 1.0s 下限；仍超长则轻微超长并说明，
        //    不再牺牲片头/钩子/产品/片尾结构。
        if (acc > p.getMaxSec() && !tl.isEmpty()) {
            double over = acc - p.getMaxSec();
            guard = 0;
            while (over > 0.05 && guard++ < 100) {
                int shrinkAt = -1;
                for (int i = 0; i < tl.size(); i++) {
                    String slot = tl.get(i).getSlot();
                    if (!"product".equals(slot) && !"endcard".equals(slot) && !"hook".equals(slot)) {
                        if (shrinkAt < 0 || tl.get(i).getDuration() > tl.get(shrinkAt).getDuration()) shrinkAt = i;
                    }
                }
                if (shrinkAt < 0) shrinkAt = tl.size() - 1;
                Segment target = tl.get(shrinkAt);
                double available = target.getDuration() - 1.0;
                if (available <= 0.05) break;
                double cut = Math.min(over, available);
                target.setDuration(round(target.getDuration() - cut));
                over -= cut;
            }
            acc = round(p.getMaxSec() - Math.max(0, over));
            if (over > 0.05) {
                plan.getNotes().add(String.format("（降级）时长超出 %.1fs 且各段均已缩至 1.0s 下限，为保留片头/钩子/产品/片尾节奏允许轻微超长", over));
            }
        }

        guard = 0;
        int rounds = maxRounds;
        double trimLastDuration = tl.isEmpty() ? -1 : tl.get(tl.size() - 1).getDuration();
        while (acc < p.getMinSec() && guard++ < 500) {
            int insertAt = tl.size() - ("endcard".equals(tl.get(tl.size() - 1).getSlot()) ? 1 : 0);
            Long lastMid = insertAt > 0 ? tl.get(insertAt - 1).getMaterialId() : null;
            Segment s = takeNext(bodyCur, lastMid, p, rnd, rounds, trimLastDuration);
            if (s == null) s = takeNext(celebCur, lastMid, p, rnd, rounds, trimLastDuration);
            if (s == null) {
                rounds += 4;
                if (rounds > 40) break;
                continue;
            }
            tl.add(insertAt, s);
            acc += s.getDuration();
        }
        if (acc < p.getMinSec()) {
            plan.getNotes().add(String.format("素材不足，实际时长 %.1fs 低于下限 %ds，建议补素材", acc, p.getMinSec()));
        }
        return round(acc);
    }

    private List<Plan.VoiceSegment> planVoiceSegments(List<Material> candidates, double targetSec,
                                                       Long explicitId, Plan plan) {
        if (candidates == null || candidates.isEmpty() || targetSec <= 0) return List.of();
        List<Material> ordered = new ArrayList<>();
        Material explicit = candidates.stream().filter(item -> Objects.equals(item.getId(), explicitId)).findFirst().orElse(null);
        if (explicit != null) ordered.add(explicit);
        candidates.stream()
                .filter(item -> item != explicit)
                .sorted(Comparator.comparingDouble(this::mediaDuration).reversed()
                        .thenComparing(item -> item.getId() == null ? Long.MAX_VALUE : item.getId()))
                .forEach(ordered::add);
        List<Plan.VoiceSegment> result = new ArrayList<>();
        double timeline = 0;
        Set<Long> used = new HashSet<>();
        for (Material material : ordered) {
            if (material == null || material.getId() == null || !used.add(material.getId())) continue;
            double available = mediaDuration(material);
            if (available < 0.8) continue;
            double duration = Math.min(available, targetSec - timeline);
            if (duration < 0.8) break;
            Plan.VoiceSegment segment = new Plan.VoiceSegment();
            segment.setMaterialId(material.getId());
            segment.setMaterialName(material.getName());
            segment.setFilePath(material.getFilePath());
            segment.setTimelineStart(round(timeline));
            segment.setDuration(round(duration));
            segment.setSourceStart(0);
            segment.setSourceDuration(round(available));
            result.add(segment);
            timeline += duration;
            if (timeline + 0.5 >= targetSec) break;
        }
        if (timeline + 0.5 < targetSec) {
            plan.getNotes().add("口播候选仅覆盖 " + round(timeline) + "s，计划 " + round(targetSec)
                    + "s；未找到更多不同口播素材，已拒绝单段循环并要求补充音频或选择 BGM");
        }
        return List.copyOf(result);
    }

    private double mediaDuration(Material material) {
        return material.getDurationSec() == null ? 0 : Math.max(0, material.getDurationSec());
    }

    private boolean sameAudioSource(Material left, Material right) {
        if (left == null || right == null) return false;
        if (Objects.equals(left.getId(), right.getId())) return true;
        String leftPath = left.getFilePath();
        String rightPath = right.getFilePath();
        return leftPath != null && rightPath != null
                && leftPath.trim().equalsIgnoreCase(rightPath.trim());
    }

    private Material longestAudio(List<Material> audio) {
        return audio.stream().max(Comparator.comparingDouble(this::mediaDuration)).orElse(null);
    }

    private Material pick(List<Material> list, Random rnd) {
        if (list == null || list.isEmpty()) return null;
        return list.get(rnd.nextInt(list.size()));
    }

    private Material pickById(List<Material> list, Long id, Random rnd) {
        if (list == null || list.isEmpty()) return null;
        if (id != null) {
            for (Material m : list) if (id.equals(m.getId())) return m;
        }
        return list.get(rnd.nextInt(list.size()));
    }

    /** An explicitly selected background track may have been imported as voice; accept any readable audio role. */
    private Material pickExplicitAudio(Pool pool, Long explicitId) {
        if (explicitId == null) return null;
        for (Material material : concatAudio(pool)) {
            if (explicitId.equals(material.getId())) return material;
        }
        return null;
    }

    private List<Material> concatAudio(Pool pool) {
        List<Material> audio = new ArrayList<>(pool.bgm);
        audio.addAll(pool.voice);
        return audio;
    }

    /**
     * Content-driven BGM selection: prefer the least-used BGM whose name/tags match the brief's
     * mood keywords. Returns null when there is no intent or no match, so the existing least-used
     * (and longest-voice) fallbacks stay intact.
     */
    /**
     * A batch renders plans with increasing variants. Seed each new plan so equal-usage candidates
     * rotate deterministically instead of every plan selecting the first row in repository order.
     * Explicit material IDs still bypass this preference in the selection helpers.
     */
    private void seedAudioRotation(Plan plan, Pool pool, int variant) {
        int rotation = (int) Math.floorMod(plan.getSeed() ^ (variant * 104729L), Integer.MAX_VALUE);
        seedAudioRotation(plan.audioUsage, "voice", pool.getVoice(), rotation);
        seedAudioRotation(plan.audioUsage, "bgm", pool.getBgm(), rotation);
    }

    private void applyRecentAudioUsage(Plan plan, Map<String, Integer> recentUsage) {
        if (recentUsage == null || recentUsage.isEmpty()) return;
        recentUsage.forEach((key, count) -> {
            if (key != null && count != null && count > 0) plan.audioUsage.merge(key, count, Integer::sum);
        });
        plan.getNotes().add("已按近期成片音频使用记录轮换未指定音轨");
    }

    private void seedAudioRotation(Map<String, Integer> usage, String category, List<Material> materials, int variant) {
        if (materials == null || materials.size() < 2) return;
        List<Material> ordered = materials.stream()
                .sorted(Comparator.comparing(material -> material.getId() == null ? Long.MAX_VALUE : material.getId()))
                .toList();
        int target = Math.floorMod(variant, ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            usage.put(category + ":" + ordered.get(i).getId(), Math.floorMod(i - target, ordered.size()));
        }
    }

    private Material pickIntentBgm(List<Material> bgms, AudioIntent intent, Map<String, Integer> usage) {
        if (intent == null || !intent.isPresent() || intent.getMoodKeywords().isEmpty()
                || bgms == null || bgms.isEmpty()) {
            return null;
        }
        List<Material> matches = bgms.stream().filter(m -> matchesIntent(m, intent)).toList();
        if (matches.isEmpty()) return null;
        Material best = null;
        int bestCount = Integer.MAX_VALUE;
        for (Material material : matches) {
            int count = usage.getOrDefault("bgm:" + material.getId(), 0);
            if (count < bestCount) { bestCount = count; best = material; }
        }
        return best != null ? best : matches.get(0);
    }

    private boolean matchesIntent(Material material, AudioIntent intent) {
        if (material == null || intent == null || intent.getMoodKeywords().isEmpty()) return false;
        String text = (String.valueOf(material.getName()) + " " + String.valueOf(material.getTags()))
                .toLowerCase(Locale.ROOT);
        for (String keyword : intent.getMoodKeywords()) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Pick the least-used material from a list, using usage tracking across a batch. */
    private Material pickLeastUsed(List<Material> list, Long explicitId,
                                    Map<String, Integer> usage, String category) {
        if (list == null || list.isEmpty()) return null;
        if (explicitId != null) {
            for (Material m : list) if (explicitId.equals(m.getId())) return m;
        }
        Material best = null;
        int bestCount = Integer.MAX_VALUE;
        for (Material m : list) {
            int count = usage.getOrDefault(category + ":" + m.getId(), 0);
            if (count < bestCount) { bestCount = count; best = m; }
        }
        return best != null ? best : list.get(0);
    }

    /** Generated AI voices are never an automatic narration candidate for later jobs. */
    private List<Material> selectableVoices(List<Material> voices, Long explicitId) {
        if (voices == null || voices.isEmpty()) return List.of();
        return voices.stream().filter(material -> explicitId != null && explicitId.equals(material.getId())
                        || material.getTags() == null || !material.getTags().contains("自动配音"))
                .toList();
    }

    /** Pick a requested human narration exactly; missing explicit selections never silently rotate. */
    private Material pickExplicitVoice(List<Material> list, Long explicitId, Plan plan, String category) {
        if (list == null || list.isEmpty()) {
            if (explicitId != null) {
                plan.getNotes().add("指定口播人声不可用：素材库中没有可读取的人声素材；已停止自动替换");
            }
            return null;
        }
        if (explicitId != null) {
            for (Material m : list) if (explicitId.equals(m.getId())) return m;
            plan.getNotes().add("指定口播人声不可用：请确认该素材仍可读取且角色为人声；已停止自动替换");
            return null;
        }
        Material best = null;
        int bestCount = Integer.MAX_VALUE;
        for (Material m : list) {
            int count = plan.audioUsage.getOrDefault(category + ":" + m.getId(), 0);
            if (count < bestCount) { bestCount = count; best = m; }
        }
        return best != null ? best : list.get(0);
    }

    /** Pick least-used voice excluding one that was already used for main narration. */
    private Material pickLeastUsedExcluding(List<Material> list, Long explicitId,
                                             Set<String> usedVoices, Material exclude,
                                             Map<String, Integer> usage, String category) {
        if (list == null || list.isEmpty()) return null;
        if (explicitId != null) {
            for (Material m : list) {
                if (!explicitId.equals(m.getId())) continue;
                if (exclude != null && sameAudioSource(m, exclude)) return null;
                return m;
            }
        }
        List<Material> candidates = new ArrayList<>();
        for (Material m : list) {
            if (exclude != null && Objects.equals(m.getId(), exclude.getId())) continue;
            candidates.add(m);
        }
        if (candidates.isEmpty()) return null;
        Material best = null;
        int bestCount = Integer.MAX_VALUE;
        for (Material m : candidates) {
            int count = usage.getOrDefault(category + ":" + m.getId(), 0);
            if (count < bestCount) { bestCount = count; best = m; }
        }
        return best != null ? best : candidates.get(0);
    }

    private double round(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
