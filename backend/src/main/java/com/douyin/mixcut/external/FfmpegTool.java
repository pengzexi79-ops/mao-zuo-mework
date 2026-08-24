package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ffmpeg / ffprobe 封装。所有真实的视频操作都落在这里。
 */
@Slf4j
@Component
public class FfmpegTool {

    private final AppProps props;
    private final ProcRunner runner;
    private final TaskAwareProcRunner taskRunner;
    private final ObjectMapper om = new ObjectMapper();

    public FfmpegTool(AppProps props, ProcRunner runner) {
        this(props, runner, null);
    }

    @Autowired
    public FfmpegTool(AppProps props, ProcRunner runner, TaskAwareProcRunner taskRunner) {
        this.props = props;
        this.runner = runner;
        this.taskRunner = taskRunner;
    }
    private final Map<String, CachedProbe> probeCache = new ConcurrentHashMap<>();
    private static final long PROBE_CACHE_MS = 30_000;

    private record CachedProbe(long modifiedAt, long size, long cachedAt, MediaInfo info) {}

    @Data
    public static class MediaInfo {
        private double duration;
        private int width;
        private int height;
        private boolean hasVideo;
        private boolean hasAudio;
        private boolean readableImage;
        private double fps = 30;
        private double videoDuration;
        private double audioDuration;
        private double videoStartSec;
        private double audioStartSec;
        private String audioCodec;
        private int audioSampleRate;
        private int audioChannels;
    }

    @Data
    public static class AudioQuality {
        private boolean readable;
        private double maxSilenceSec;
        /** 全片音量均值（dB，volumedetect 的 mean_volume）。null 表示无法测得。 */
        private Double meanVolumeDb;
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class VideoQuality {
        private boolean readable;
        private double blackSec;
        /** 累计检测到的异常纯红/品红画面时长（秒）。错误占位帧通常表现为整帧纯红或纯品红。 */
        private double redMagentaSec;
        /** 累计检测到的冻结/静止画面时长（秒，freezedetect）。数字静帧循环、静态字幕页、占位画面都是这种形态。 */
        private double frozenSec;
        /** 累计检测到的任意纯色画面时长（秒，1fps 采样）。整段纯色通常来自色卡/占位/测试素材。 */
        private double solidColorSec;
    }

    /** 一个被 ffmpeg 场景检测识别出的切点（时间戳 + 变化分数）。 */
    @Data
    public static class SceneCut {
        private double time;
        private double score;
    }

    public boolean ffmpegAvailable() {
        return runner.available(props.getFfmpeg(), "-version");
    }

    public boolean ffprobeAvailable() {
        return runner.available(props.getFfprobe(), "-version");
    }

    /** 读取媒体信息 */
    public MediaInfo probe(String file) {
        return probe(file, ProcessRegistry.CancellationContext.none());
    }

    public MediaInfo probe(String file, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        Path path = Path.of(file == null ? "" : file).toAbsolutePath().normalize();
        try {
            if (java.nio.file.Files.isRegularFile(path)) {
                long modified = java.nio.file.Files.getLastModifiedTime(path).toMillis();
                long size = java.nio.file.Files.size(path);
                CachedProbe cached = probeCache.get(path.toString());
                if (cached != null && cached.modifiedAt == modified && cached.size == size
                        && System.currentTimeMillis() - cached.cachedAt < PROBE_CACHE_MS) {
                    return copyInfo(cached.info);
                }
            }
        } catch (Exception ignored) { }
        MediaInfo info = new MediaInfo();
        List<String> cmd = List.of(props.getFfprobe(), "-v", "error",
                "-print_format", "json", "-show_format", "-show_streams", file);
        ProcRunner.SeparateResult r = runTaskSeparate(cmd, 60, context);
        context.throwIfCancelled();
        if (!r.ok()) {
            log.warn("probe command failed {}: {}", file, tail(r.err()));
            return info;
        }
        boolean parsed = false;
        try {
            fillInfo(info, om.readTree(r.out()));
            parsed = true;
        } catch (Exception e) {
            // 容错：极少数环境仍可能在 stdout 混入非 JSON 文本（旧版 runner 合并流），提取 JSON 子串重试。
            String raw = r.out();
            int s = raw.indexOf('{');
            int e2 = raw.lastIndexOf('}');
            if (s >= 0 && e2 > s) {
                try {
                    fillInfo(info, om.readTree(raw.substring(s, e2 + 1)));
                    parsed = true;
                } catch (Exception ex) {
                    log.warn("probe parse failed {}: {}", file, ex.toString());
                }
            } else {
                log.warn("probe parse failed {}: {}", file, e.toString());
            }
        }
        boolean empty = !info.isHasVideo() && !info.isHasAudio() && info.getDuration() <= 0;
        if (parsed && !empty) {
            try {
                if (java.nio.file.Files.isRegularFile(path)) {
                    probeCache.put(path.toString(), new CachedProbe(
                            java.nio.file.Files.getLastModifiedTime(path).toMillis(),
                            java.nio.file.Files.size(path), System.currentTimeMillis(), copyInfo(info)));
                    if (probeCache.size() > 512) probeCache.clear();
                }
            } catch (Exception ignored) { }
        } else if (parsed && empty) {
            // 空 MediaInfo 不写缓存：否则 30s 内 isUsableClip 全部拿到空信息，导致切片被误判跳过。
            log.warn("probe returned empty media info for {} (parse ok but no video/audio/duration), not cached", file);
        }
        return info;
    }

    private MediaInfo copyInfo(MediaInfo source) {
        MediaInfo copy = new MediaInfo();
        copy.setDuration(source.getDuration()); copy.setWidth(source.getWidth()); copy.setHeight(source.getHeight());
        copy.setHasVideo(source.isHasVideo()); copy.setHasAudio(source.isHasAudio()); copy.setReadableImage(source.isReadableImage());
        copy.setFps(source.getFps()); copy.setVideoDuration(source.getVideoDuration()); copy.setAudioDuration(source.getAudioDuration());
        copy.setVideoStartSec(source.getVideoStartSec()); copy.setAudioStartSec(source.getAudioStartSec()); copy.setAudioCodec(source.getAudioCodec());
        copy.setAudioSampleRate(source.getAudioSampleRate()); copy.setAudioChannels(source.getAudioChannels());
        return copy;
    }

    /** 从 ffprobe JSON 填充媒体信息（probe 主路径与容错路径共用）。 */
    private void fillInfo(MediaInfo info, JsonNode root) {
        JsonNode fmt = root.path("format");
        if (fmt.hasNonNull("duration")) info.setDuration(fmt.get("duration").asDouble(0));
        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asText("");
            if ("video".equals(type) && !info.isHasVideo()) {
                info.setHasVideo(true);
                info.setReadableImage(true);
                info.setWidth(s.path("width").asInt(0));
                info.setHeight(s.path("height").asInt(0));
                String rate = s.path("r_frame_rate").asText("30/1");
                info.setFps(parseRate(rate));
                info.setVideoDuration(streamDuration(s));
                info.setVideoStartSec(streamStart(s));
                if (info.getDuration() <= 0 && info.getVideoDuration() > 0) info.setDuration(info.getVideoDuration());
            } else if ("audio".equals(type) && !info.isHasAudio()) {
                info.setHasAudio(true);
                info.setAudioDuration(streamDuration(s));
                info.setAudioStartSec(streamStart(s));
                info.setAudioCodec(s.path("codec_name").asText(null));
                info.setAudioSampleRate(s.path("sample_rate").asInt(0));
                info.setAudioChannels(s.path("channels").asInt(0));
                if (info.getDuration() <= 0 && info.getAudioDuration() > 0) info.setDuration(info.getAudioDuration());
            }
        }
        if (info.isHasVideo() && info.getVideoDuration() <= 0) info.setVideoDuration(info.getDuration());
        if (info.isHasAudio() && info.getAudioDuration() <= 0) info.setAudioDuration(info.getDuration());
    }
    private double streamDuration(JsonNode stream) {
        return stream.hasNonNull("duration") ? stream.get("duration").asDouble(0) : 0;
    }

    private double streamStart(JsonNode stream) {
        return stream.hasNonNull("start_time") ? stream.get("start_time").asDouble(0) : 0;
    }

    /**
     * 安全地做 ffmpeg 场景检测：用 select=gt(scene,threshold) + showinfo 识别镜头切点。
     * 失败或无可读输出时返回空列表，由调用方走均匀切片兜底，绝不抛出异常打断分析流程。
     */
    public List<SceneCut> detectSceneCuts(String file, double threshold) {
        return detectSceneCuts(file, threshold, ProcessRegistry.CancellationContext.none());
    }

    public List<SceneCut> detectSceneCuts(String file, double threshold,
                                          ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        List<SceneCut> cuts = new ArrayList<>();
        try {
            List<String> cmd = List.of(props.getFfmpeg(), "-hide_banner", "-i", file,
                    "-vf", "select='gt(scene," + trimNum(threshold) + ")',showinfo",
                    "-an", "-f", "null", "-");
            ProcRunner.Result result = runTask(cmd, 600, context);
            context.throwIfCancelled();
            if (!result.ok()) return cuts;
            Pattern timePattern = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)");
            Pattern scorePattern = Pattern.compile("scene_score=([0-9]+(?:\\.[0-9]+)?)");
            Double pendingTime = null;
            for (String line : result.out().split("\\R")) {
                Matcher timeMatch = timePattern.matcher(line);
                if (timeMatch.find()) {
                    try {
                        pendingTime = Double.parseDouble(timeMatch.group(1));
                    } catch (NumberFormatException ignore) {
                        pendingTime = null;
                    }
                    continue;
                }
                Matcher scoreMatch = scorePattern.matcher(line);
                if (scoreMatch.find() && pendingTime != null) {
                    SceneCut cut = new SceneCut();
                    cut.setTime(pendingTime);
                    try {
                        cut.setScore(Double.parseDouble(scoreMatch.group(1)));
                    } catch (NumberFormatException ignore) {
                        cut.setScore(threshold);
                    }
                    cuts.add(cut);
                    pendingTime = null;
                }
            }
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("scene detection failed for {}: {}", file, e.toString());
            return List.of();
        }
        context.throwIfCancelled();
        return cuts;
    }

    /** Detects long silent sections in the decoded final audio. */
    public AudioQuality audioQuality(Path file) {
        return audioQuality(file, ProcessRegistry.CancellationContext.none());
    }

    public AudioQuality audioQuality(Path file, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        AudioQuality quality = new AudioQuality();
        ProcRunner.Result result = runTask(List.of(props.getFfmpeg(), "-v", "info", "-i", file.toString(),
                "-af", "silencedetect=noise=-45dB:d=0.5,volumedetect", "-f", "null", "-"), 900, context);
        if (!result.ok()) {
            quality.getWarnings().add("音频质检无法读取成片");
            return quality;
        }
        quality.setReadable(true);
        String out = result.out();
        double silenceStart = -1;
        Pattern startPattern = Pattern.compile("silence_start:\\s*([0-9.]+)");
        Pattern endPattern = Pattern.compile("silence_end:\\s*([0-9.]+)");
        Pattern durationPattern = Pattern.compile("silence_duration:\\s*([0-9.]+)");
        for (String line : out.split("\\R")) {
            Matcher startMatch = startPattern.matcher(line);
            if (startMatch.find()) {
                try { silenceStart = Double.parseDouble(startMatch.group(1)); } catch (Exception ignore) { }
            }
            Matcher durationMatch = durationPattern.matcher(line);
            if (durationMatch.find()) {
                try { quality.setMaxSilenceSec(Math.max(quality.getMaxSilenceSec(), Double.parseDouble(durationMatch.group(1)))); } catch (Exception ignore) { }
                silenceStart = -1;
                continue;
            }
            Matcher endMatch = endPattern.matcher(line);
            if (endMatch.find() && silenceStart >= 0) {
                try {
                    double silenceEnd = Double.parseDouble(endMatch.group(1));
                    quality.setMaxSilenceSec(Math.max(quality.getMaxSilenceSec(), silenceEnd - silenceStart));
                } catch (Exception ignore) { }
                silenceStart = -1;
            }
        }
        Matcher volumeMatch = Pattern.compile("mean_volume:\\s*([-0-9.]+)\\s*dB").matcher(out);
        if (volumeMatch.find()) {
            try { quality.setMeanVolumeDb(Double.parseDouble(volumeMatch.group(1))); } catch (Exception ignore) { }
        }
        context.throwIfCancelled();
        MediaInfo info = probe(file.toString(), context);
        if (silenceStart >= 0 && info.getDuration() > silenceStart) {
            quality.setMaxSilenceSec(Math.max(quality.getMaxSilenceSec(), info.getDuration() - silenceStart));
        }
        return quality;
    }

    /**
     * Detects prolonged near-black video sections in a decoded final output.
     * The same single pass also samples one frame per second (fps=1) and runs signalstats to
     * flag abnormal solid red/magenta error frames, and runs freezedetect at full rate to
     * flag frozen / near-static sections, keeping the extra detection low-cost.
     */
    public VideoQuality videoQuality(Path file) {
        return videoQuality(file, ProcessRegistry.CancellationContext.none());
    }

    public VideoQuality videoQuality(Path file, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        VideoQuality quality = new VideoQuality();
        ProcRunner.Result result = runTask(List.of(props.getFfmpeg(), "-v", "info", "-i", file.toString(),
                "-vf", "blackdetect=d=0.4:pix_th=0.10,freezedetect=n=0.003:d=1.0,fps=1,signalstats,metadata=print",
                "-an", "-f", "null", "-"), 900, context);
        context.throwIfCancelled();
        if (!result.ok()) return quality;
        quality.setReadable(true);
        String out = result.out();
        for (String line : out.split("\\R")) {
            int start = line.indexOf("black_duration:");
            if (start < 0) continue;
            String value = line.substring(start + 15).trim();
            int space = value.indexOf(' ');
            try { quality.setBlackSec(quality.getBlackSec() + Double.parseDouble(space >= 0 ? value.substring(0, space) : value)); }
            catch (Exception ignore) { }
        }
        quality.setFrozenSec(frozenSec(out, file, context));
        double[] solid = solidColorSeconds(out);
        quality.setRedMagentaSec(solid[0]);
        quality.setSolidColorSec(solid[1]);
        return quality;
    }

    // Conservative thresholds for solid color-frame detection (8-bit limited-range YUV).
    private static final double SOLID_RANGE_MAX = 6;
    private static final double RED_V_MIN = 160;
    private static final double RED_U_MAX = 135;
    private static final double RED_Y_MAX = 170;
    private static final double MAGENTA_U_MIN = 160;
    private static final double MAGENTA_V_MIN = 160;
    private static final double MAGENTA_Y_MAX = 180;

    /**
     * Sums sampled seconds of near-uniform frames from signalstats output.
     * Returns {redMagentaSec, anyColorSec}: the first counts strongly red-/magenta-dominant
     * error frames, the second counts any uniform color frame (color cards, placeholders).
     */
    private double[] solidColorSeconds(String output) {
        double redMagenta = 0;
        double anyColor = 0;
        Double ymin = null, ymax = null, yavg = null, uavg = null, vavg = null;
        Double umin = null, umax = null, vmin = null, vmax = null;
        boolean frameOpen = false;
        for (String line : output.split("\\R")) {
            if (line.contains("frame:")) {
                int kind = classifySolidFrame(ymin, ymax, umin, umax, vmin, vmax, yavg, uavg, vavg);
                if (kind > 0) anyColor += 1.0;
                if (kind > 1) redMagenta += 1.0;
                ymin = ymax = yavg = uavg = vavg = null;
                umin = umax = vmin = vmax = null;
                frameOpen = true;
                continue;
            }
            if (!frameOpen) continue;
            if (line.contains("YMIN=")) ymin = parseStat(line);
            else if (line.contains("YMAX=")) ymax = parseStat(line);
            else if (line.contains("YAVG=")) yavg = parseStat(line);
            else if (line.contains("UMIN=")) umin = parseStat(line);
            else if (line.contains("UMAX=")) umax = parseStat(line);
            else if (line.contains("UAVG=")) uavg = parseStat(line);
            else if (line.contains("VMIN=")) vmin = parseStat(line);
            else if (line.contains("VMAX=")) vmax = parseStat(line);
            else if (line.contains("VAVG=")) vavg = parseStat(line);
        }
        int kind = classifySolidFrame(ymin, ymax, umin, umax, vmin, vmax, yavg, uavg, vavg);
        if (kind > 0) anyColor += 1.0;
        if (kind > 1) redMagenta += 1.0;
        return new double[]{redMagenta, anyColor};
    }

    /**
     * Classifies one sampled frame: 0 = not uniform, 1 = uniform color (any),
     * 2 = uniform strongly red or magenta (typical error-placeholder frame).
     */
    private int classifySolidFrame(Double ymin, Double ymax, Double umin, Double umax,
                                   Double vmin, Double vmax, Double yavg, Double uavg, Double vavg) {
        if (ymin == null || ymax == null || umin == null || umax == null || vmin == null || vmax == null
                || yavg == null || uavg == null || vavg == null) return 0;
        boolean solid = (ymax - ymin) <= SOLID_RANGE_MAX
                && (umax - umin) <= SOLID_RANGE_MAX
                && (vmax - vmin) <= SOLID_RANGE_MAX;
        if (!solid) return 0;
        boolean red = vavg >= RED_V_MIN && uavg <= RED_U_MAX && yavg <= RED_Y_MAX;
        boolean magenta = uavg >= MAGENTA_U_MIN && vavg >= MAGENTA_V_MIN && yavg <= MAGENTA_Y_MAX;
        return (red || magenta) ? 2 : 1;
    }

    /**
     * Sums frozen (near-static) seconds from freezedetect output. A freeze still running at
     * end of stream is closed against the probed duration, mirroring the trailing-silence
     * handling in {@link #audioQuality(Path)}.
     */
    private double frozenSec(String output, Path file, ProcessRegistry.CancellationContext context) {
        double frozen = 0;
        double freezeStart = -1;
        Pattern startPattern = Pattern.compile("freeze_start:\\s*([0-9.]+)");
        Pattern durationPattern = Pattern.compile("freeze_duration:\\s*([0-9.]+)");
        Pattern endPattern = Pattern.compile("freeze_end:\\s*([0-9.]+)");
        for (String line : output.split("\\R")) {
            Matcher startMatch = startPattern.matcher(line);
            if (startMatch.find()) {
                try { freezeStart = Double.parseDouble(startMatch.group(1)); } catch (Exception ignore) { }
            }
            Matcher durationMatch = durationPattern.matcher(line);
            if (durationMatch.find()) {
                try { frozen += Double.parseDouble(durationMatch.group(1)); } catch (Exception ignore) { }
                freezeStart = -1;
                continue;
            }
            Matcher endMatch = endPattern.matcher(line);
            if (endMatch.find() && freezeStart >= 0) {
                try {
                    double freezeEnd = Double.parseDouble(endMatch.group(1));
                    frozen += Math.max(0, freezeEnd - freezeStart);
                } catch (Exception ignore) { }
                freezeStart = -1;
            }
        }
        if (freezeStart >= 0) {
            double duration = probe(file.toString(), context).getDuration();
            if (duration > freezeStart) frozen += duration - freezeStart;
        }
        return frozen;
    }

    private Double parseStat(String line) {
        int idx = line.indexOf('=');
        if (idx < 0 || idx + 1 >= line.length()) return null;
        try {
            return Double.parseDouble(line.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseRate(String r) {
        try {
            String[] p = r.split("/");
            double a = Double.parseDouble(p[0]);
            double b = p.length > 1 ? Double.parseDouble(p[1]) : 1;
            if (b == 0) return 30;
            double v = a / b;
            return (v <= 0 || v > 240) ? 30 : v;
        } catch (Exception e) {
            return 30;
        }
    }

    /**
     * 切片并统一规格：把 src 从 start 开始截取 dur 秒，缩放/裁剪成竖屏 w x h，去掉原声（音轨后面统一铺）。
     * 统一规格是拼接不炸的前提——这一步偷懒后面 concat 一定出问题。
     */
    public boolean cutNormalize(String src, double start, double dur, int w, int h, double fps, Path dst) {
        return cutNormalize(src, start, dur, w, h, fps, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean cutNormalize(String src, double start, double dur, int w, int h, double fps, Path dst,
                                ProcessRegistry.CancellationContext context) {
        return cutNormalize(src, start, dur, w, h, fps, false, dst, context);
    }

    /** Normalizes one source segment; preserving audio also pads silent sources for concat compatibility. */
    public boolean cutNormalize(String src, double start, double dur, int w, int h, double fps, boolean preserveAudio, Path dst) {
        return cutNormalize(src, start, dur, w, h, fps, preserveAudio, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean cutNormalize(String src, double start, double dur, int w, int h, double fps, boolean preserveAudio, Path dst,
                                ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        String vf = String.format(
                "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1,fps=%s,format=yuv420p",
                w, h, w, h, trimNum(fps));
        List<String> cmd = new ArrayList<>(List.of(
                props.getFfmpeg(), "-y",
                "-ss", trimNum(start),
                "-t", trimNum(dur),
                "-i", src));
        boolean sourceHasAudio = preserveAudio && probe(src, context).isHasAudio();
        context.throwIfCancelled();
        if (preserveAudio && !sourceHasAudio) cmd.addAll(List.of("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"));
        cmd.addAll(List.of("-vf", vf));
        if (preserveAudio) {
            cmd.addAll(sourceHasAudio ? List.of("-map", "0:v:0", "-map", "0:a:0?") : List.of("-map", "0:v:0", "-map", "1:a:0", "-shortest"));
            cmd.addAll(List.of("-c:a", "aac", "-ar", "44100", "-ac", "2"));
        } else cmd.add("-an");
        cmd.addAll(List.of(
                // Slices are throwaway intermediates but feed the next encode: veryfast keeps
                // generation fast while crf 26 avoids the heavy quality loss of ultrafast 28.
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "26",
                "-video_track_timescale", "90000",
                dst.toString()));
        context.throwIfCancelled();
        ProcRunner.Result r = runTask(cmd, 120, context);
        context.throwIfCancelled();
        if (!r.ok()) log.warn("cutNormalize failed [{}]: {}", src, tail(r.out()));
        return r.ok() && dst.toFile().exists() && dst.toFile().length() > 1024;
    }

    /**
     * Rebuilds a video from explicitly retained source ranges. The caller computes the ranges
     * from user-controlled edit semantics; this method only serializes validated numeric values
     * into FFmpeg's filter graph and never accepts a raw filter string from a browser.
     */
    public boolean editVideoRanges(String src, List<double[]> ranges, boolean keepAudio, Path dst) {
        return editVideoRanges(src, ranges, keepAudio, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean editVideoRanges(String src, List<double[]> ranges, boolean keepAudio, Path dst,
                                   ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        if (ranges == null || ranges.isEmpty()) return false;
        List<String> filters = new ArrayList<>();
        StringBuilder videoInputs = new StringBuilder();
        StringBuilder audioInputs = new StringBuilder();
        for (int index = 0; index < ranges.size(); index++) {
            double[] range = ranges.get(index);
            if (range == null || range.length < 2 || range[1] - range[0] < 0.05) return false;
            String start = trimNum(Math.max(0, range[0]));
            String end = trimNum(Math.max(range[0], range[1]));
            filters.add(String.format(Locale.ROOT,
                    "[0:v]trim=start=%s:end=%s,setpts=PTS-STARTPTS[v%d]", start, end, index));
            videoInputs.append("[v").append(index).append("]");
            if (keepAudio) {
                filters.add(String.format(Locale.ROOT,
                        "[0:a]atrim=start=%s:end=%s,asetpts=PTS-STARTPTS[a%d]", start, end, index));
                audioInputs.append("[a").append(index).append("]");
            }
        }
        context.throwIfCancelled();
        int count = ranges.size();
        filters.add(videoInputs + "concat=n=" + count + ":v=1:a=0[vout]");
        if (keepAudio) filters.add(audioInputs + "concat=n=" + count + ":v=0:a=1[aout]");
        List<String> cmd = new ArrayList<>(List.of(props.getFfmpeg(), "-y", "-i", src,
                "-filter_complex", String.join(";", filters), "-map", "[vout]"));
        if (keepAudio) cmd.addAll(List.of("-map", "[aout]", "-c:a", "aac", "-b:a", "192k"));
        else cmd.add("-an");
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
                "-movflags", "+faststart", "-video_track_timescale", "90000", dst.toString()));
        context.throwIfCancelled();
        ProcRunner.Result result = runTask(cmd, 1800, context);
        context.throwIfCancelled();
        if (!result.ok()) log.warn("editVideoRanges failed: {}", tail(result.out()));
        return result.ok() && dst.toFile().exists() && dst.toFile().length() > 1024;
    }

    /** Applies a user-confirmed rectangle over one image and writes a new PNG. */
    public boolean coverImageRect(String src, int x, int y, int width, int height, String color, Path dst) {
        return coverImageRect(src, x, y, width, height, color, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean coverImageRect(String src, int x, int y, int width, int height, String color, Path dst,
                                  ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        if (width < 1 || height < 1 || x < 0 || y < 0) return false;
        String filter = String.format(Locale.ROOT, "drawbox=x=%d:y=%d:w=%d:h=%d:color=%s:t=fill", x, y, width, height, color);
        List<String> cmd = List.of(props.getFfmpeg(), "-y", "-i", src, "-vf", filter,
                "-frames:v", "1", "-f", "image2", dst.toString());
        context.throwIfCancelled();
        ProcRunner.Result result = runTask(cmd, 180, context);
        context.throwIfCancelled();
        if (!result.ok()) log.warn("coverImageRect failed: {}", tail(result.out()));
        return result.ok() && dst.toFile().exists() && dst.toFile().length() > 128;
    }

    /** Applies a user-confirmed fixed rectangle over a video for a bounded time range. */
    public boolean coverVideoRect(String src, int x, int y, int width, int height, String color,
                                  double start, double end, Path dst) {
        return coverVideoRect(src, x, y, width, height, color, start, end, dst,
                ProcessRegistry.CancellationContext.none());
    }

    public boolean coverVideoRect(String src, int x, int y, int width, int height, String color,
                                  double start, double end, Path dst,
                                  ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        if (width < 1 || height < 1 || start < 0 || end <= start) return false;
        String enable = String.format(Locale.ROOT, "between(t,%s,%s)", trimNum(start), trimNum(end));
        String filter = String.format(Locale.ROOT, "drawbox=x=%d:y=%d:w=%d:h=%d:color=%s:t=fill:enable='%s'",
                x, y, width, height, color, enable);
        List<String> cmd = List.of(props.getFfmpeg(), "-y", "-i", src, "-vf", filter,
                "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", dst.toString());
        context.throwIfCancelled();
        ProcRunner.Result result = runTask(cmd, 1800, context);
        context.throwIfCancelled();
        if (!result.ok()) log.warn("coverVideoRect failed: {}", tail(result.out()));
        return result.ok() && dst.toFile().exists() && dst.toFile().length() > 1024;
    }

    /** 图片转成一段静帧视频（Ken Burns 轻微推近，避免死板） */
    public boolean imageToClip(String src, double dur, int w, int h, double fps, Path dst) {
        return imageToClip(src, dur, w, h, fps, false, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean imageToClip(String src, double dur, int w, int h, double fps, boolean preserveAudio, Path dst) {
        return imageToClip(src, dur, w, h, fps, preserveAudio, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean imageToClip(String src, double dur, int w, int h, double fps, boolean preserveAudio, Path dst,
                               ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        int frames = Math.max(1, (int) Math.round(dur * fps));
        String vf = String.format(
                "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d," +
                        "zoompan=z='min(zoom+0.0012,1.12)':d=%d:s=%dx%d:fps=%s,setsar=1,format=yuv420p",
                w, h, w, h, frames, w, h, trimNum(fps));
        // zoompan 的 d 是每一张输入图片应输出的帧数。单图输入时不能再 loop 输入，
        // 否则每个重复输入帧都会再输出 d 帧，3 秒静帧会错误膨胀成数百秒。
        List<String> cmd = new ArrayList<>(List.of(props.getFfmpeg(), "-y", "-i", src));
        if (preserveAudio) cmd.addAll(List.of("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"));
        cmd.addAll(List.of("-vf", vf));
        if (preserveAudio) cmd.addAll(List.of("-map", "0:v:0", "-map", "1:a:0", "-shortest", "-c:a", "aac", "-ar", "44100", "-ac", "2"));
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "26",
                "-video_track_timescale", "90000", dst.toString()));
        ProcRunner.Result r = runTask(cmd, 120, context);
        if (!r.ok()) log.warn("imageToClip failed [{}]: {}", src, tail(r.out()));
        context.throwIfCancelled();
        return r.ok() && dst.toFile().exists();
    }

    /** 纯色垫片（素材不够时兜底，保证时长达标） */
    public boolean colorClip(String color, double dur, int w, int h, double fps, Path dst) {
        return colorClip(color, dur, w, h, fps, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean colorClip(String color, double dur, int w, int h, double fps, Path dst,
                             ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        List<String> cmd = List.of(props.getFfmpeg(), "-y",
                "-f", "lavfi", "-i",
                String.format("color=c=%s:s=%dx%d:r=%s:d=%s", color, w, h, trimNum(fps), trimNum(dur)),
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
                "-pix_fmt", "yuv420p", "-video_track_timescale", "90000",
                dst.toString());
        ProcRunner.Result result = runTask(cmd, 120, context);
        context.throwIfCancelled();
        return result.ok();
    }

    /**
     * concat demuxer 拼接。始终重编码并重置时间戳，不能直接 -c copy：
     * 部分来源的 MP4 时间基/P​​TS 不连续，copy concat 可能得到数小时循环或错误时长。
     */
    public boolean concat(Path listFile, Path dst) {
        return concat(listFile, dst, 30, null, false, ProcessRegistry.CancellationContext.none());
    }

    public boolean concat(Path listFile, Path dst, double fps) {
        return concat(listFile, dst, fps, null, false, ProcessRegistry.CancellationContext.none());
    }

    /**
     * 拼接中间文件只用于后续混音，使用 ultrafast 降低重复编码成本；最终字幕烧录仍使用既有质量参数。
     * subtitleFilter 只接受由 burnText 生成的受控 drawtext 片段，不能传入用户命令行参数。
     */
    public boolean concat(Path listFile, Path dst, double fps, String subtitleFilter) {
        return concat(listFile, dst, fps, subtitleFilter, false, ProcessRegistry.CancellationContext.none());
    }

    public boolean concat(Path listFile, Path dst, double fps, String subtitleFilter, boolean preserveAudio) {
        return concat(listFile, dst, fps, subtitleFilter, preserveAudio, ProcessRegistry.CancellationContext.none());
    }

    public boolean concat(Path listFile, Path dst, double fps, String subtitleFilter, boolean preserveAudio,
                          ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        String videoFilter = "setpts=PTS-STARTPTS,fps=" + trimNum(fps) + ",format=yuv420p";
        if (subtitleFilter != null && !subtitleFilter.isBlank()) videoFilter += "," + subtitleFilter;
        List<String> cmd = new ArrayList<>(List.of(props.getFfmpeg(), "-y",
                "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                "-vf", videoFilter));
        if (preserveAudio) cmd.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?", "-c:a", "aac", "-ar", "44100", "-ac", "2"));
        else cmd.add("-an");
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
                "-movflags", "+faststart", "-avoid_negative_ts", "make_zero",
                "-video_track_timescale", "90000", dst.toString()));
        ProcRunner.Result result = runTask(cmd, 1800, context);
        context.throwIfCancelled();
        if (!result.ok()) log.error("concat re-encode failed: {}", tail(result.out()));
        // 短片或纯色垫片可以小于 4KB；最终由 RenderService 的 ffprobe 时长校验决定是否可交付。
        return result.ok() && dst.toFile().exists() && dst.toFile().length() > 1024;
    }

    @Data
    public static class AudioSlice {
        private String filePath;
        private double sourceStart;
        private double duration;

        public AudioSlice(String filePath, double sourceStart, double duration) {
            this.filePath = filePath;
            this.sourceStart = sourceStart;
            this.duration = duration;
        }
    }

    /** Concatenates distinct source audio ranges without looping any source slice. */
    public boolean concatAudioSlices(List<AudioSlice> slices, Path dst) {
        return concatAudioSlices(slices, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean concatAudioSlices(List<AudioSlice> slices, Path dst, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        if (slices == null || slices.isEmpty()) return false;
        List<String> cmd = new ArrayList<>(List.of(props.getFfmpeg(), "-y"));
        List<String> filters = new ArrayList<>();
        int index = 0;
        int usable = 0;
        for (AudioSlice slice : slices) {
            if (slice == null || slice.getFilePath() == null || slice.getFilePath().isBlank() || slice.getDuration() < 0.8) continue;
            cmd.addAll(List.of("-i", slice.getFilePath()));
            filters.add(String.format(java.util.Locale.US,
                    "[%d:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,atrim=start=%s:duration=%s,asetpts=PTS-STARTPTS[a%d]",
                    index++, trimNum(Math.max(0, slice.getSourceStart())), trimNum(slice.getDuration()), usable));
            usable++;
        }
        if (usable == 0) return false;
        StringBuilder concat = new StringBuilder();
        for (int i = 0; i < usable; i++) concat.append("[a").append(i).append("]");
        concat.append("concat=n=").append(usable).append(":v=0:a=1,loudnorm=I=-16:TP=-1.5:LRA=11[aout]");
        filters.add(concat.toString());
        cmd.addAll(List.of("-filter_complex", String.join(";", filters), "-map", "[aout]", "-c:a", "aac", "-b:a", "192k", dst.toString()));
        ProcRunner.Result result = runTask(cmd, 900, context);
        context.throwIfCancelled();
        if (!result.ok()) log.warn("concatAudioSlices failed: {}", tail(result.out()));
        return result.ok();
    }

    /**
     * 合成音轨：视频 + 人声(可选) + BGM(可选)。
     * 人声原音量，BGM 压到 bgmVol（默认 0.22），两条混合后再对齐视频时长。
     */
    public boolean muxAudio(Path video, String voice, String bgm, double bgmVol, double videoDur, Path dst) {
        return muxAudio(video, voice, bgm, bgmVol, false, null, 0, 0, 1.0, videoDur, dst);
    }

    /** Backward-compatible mixer entry point without an explicit ducking flag. */
    public boolean muxAudio(Path video, String voice, String bgm, double bgmVol,
                            String hookAudio, double hookStartSec, double hookEndSec, double hookVolume,
                            double videoDur, Path dst) {
        return muxAudio(video, voice, bgm, bgmVol, false, hookAudio, hookStartSec, hookEndSec, hookVolume, videoDur, dst);
    }

    /**
     * Mixes one authoritative narration layer, one looped BGM layer, and an optional independent hook clip.
     * The hook is delayed and trimmed in the filter graph, so it cannot drift into a custom intro or outlive video.
     */
    public boolean muxAudio(Path video, String voice, String bgm, double bgmVol, boolean duckBgm,
                            String hookAudio, double hookStartSec, double hookEndSec, double hookVolume,
                            double videoDur, Path dst) {
        return muxAudio(video, voice, bgm, bgmVol, duckBgm, hookAudio, hookStartSec, hookEndSec, hookVolume,
                videoDur, dst, ProcessRegistry.CancellationContext.none());
    }

    public boolean muxAudio(Path video, String voice, String bgm, double bgmVol, boolean duckBgm,
                            String hookAudio, double hookStartSec, double hookEndSec, double hookVolume,
                            double videoDur, Path dst, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getFfmpeg());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(video.toString());

        boolean hasVoice = voice != null && !voice.isBlank();
        boolean hasBgm = bgm != null && !bgm.isBlank();
        boolean hasHook = hookAudio != null && !hookAudio.isBlank()
                && hookEndSec > hookStartSec && hookStartSec < videoDur;

        if (!hasVoice && !hasBgm && !hasHook) {
            // 没音频就补静音轨，抖音端无音轨的视频容易被判低质
            cmd.addAll(List.of("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"));
            cmd.addAll(List.of("-shortest", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", dst.toString()));
            return runTask(cmd, 600, context).ok();
        }

        int idx = 1;
        int voiceIdx = -1, bgmIdx = -1, hookIdx = -1;
        if (hasVoice) {
            cmd.addAll(List.of("-i", voice));
            voiceIdx = idx++;
        }
        if (hasBgm) {
            cmd.addAll(List.of("-stream_loop", "-1", "-i", bgm));
            bgmIdx = idx++;
        }
        if (hasHook) {
            cmd.addAll(List.of("-i", hookAudio));
            hookIdx = idx++;
        }

        String filter;
        if (hasVoice && hasBgm) {
            double fadeOutStart = Math.max(0, videoDur - 2);
            String voiceChain = String.format("[%d:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,apad,atrim=0:%s,loudnorm=I=-16:TP=-1.5:LRA=11[a0]",
                    voiceIdx, trimNum(videoDur));
            String bgmChain = String.format("[%d:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,volume=%s,afade=t=in:st=0:d=1,afade=t=out:st=%s:d=2,atrim=0:%s[a1]",
                    bgmIdx, trimNum(bgmVol), trimNum(fadeOutStart), trimNum(videoDur));
            if (duckBgm) {
                // A filter pad can only be consumed once. Split narration before it feeds both the
                // side-chain detector and final mix, otherwise FFmpeg rejects the graph at runtime.
                filter = voiceChain + ";[a0]asplit=2[a0mix][a0side];" + bgmChain
                        + ";[a1][a0side]sidechaincompress=threshold=0.035:ratio=8:attack=20:release=320:makeup=1:link=average[ducked]"
                        + ";[a0mix][ducked]amix=inputs=2:duration=first:dropout_transition=0,loudnorm=I=-14:TP=-1.5:LRA=11[aout]";
            } else {
                filter = voiceChain + ";" + bgmChain
                        + ";[a0][a1]amix=inputs=2:duration=first:dropout_transition=0,loudnorm=I=-14:TP=-1.5:LRA=11[aout]";
            }
        } else if (hasVoice) {
            // RenderService verifies coverage before calling the mixer; never synthesize a silent tail here.
            filter = String.format(
                    "[%d:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,atrim=0:%s,dynaudnorm=f=200[aout]",
                    voiceIdx, trimNum(videoDur));
        } else if (hasBgm) {
            double fadeOutStart = Math.max(0, videoDur - 2);
            filter = String.format(
                    "[%d:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,volume=%s,afade=t=in:st=0:d=1,afade=t=out:st=%s:d=2,atrim=0:%s,loudnorm=I=-14:TP=-1.5:LRA=11[aout]",
                    bgmIdx, trimNum(Math.min(1.0, bgmVol * 2.2)), trimNum(fadeOutStart), trimNum(videoDur));
        } else {
            // A hook clip is a bounded overlay, so keep a silent base timeline for amix.
            filter = String.format(
                    "anullsrc=channel_layout=stereo:sample_rate=44100,atrim=0:%s[aout]",
                    trimNum(videoDur));
        }

        String outputAudio = "[aout]";
        if (hasHook) {
            double hookDuration = Math.min(videoDur, hookEndSec) - Math.max(0, hookStartSec);
            long delayMs = Math.max(0, Math.round(hookStartSec * 1000));
            filter += String.format(";[%d:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,volume=%s,atrim=0:%s,afade=t=in:st=0:d=0.12,afade=t=out:st=%s:d=0.18,adelay=%d:all=1[hook];[aout][hook]amix=inputs=2:duration=first:dropout_transition=0,loudnorm=I=-14:TP=-1.5:LRA=11[amixed]",
                    hookIdx, trimNum(hookVolume), trimNum(hookDuration), trimNum(Math.max(0, hookDuration - 0.18)), delayMs);
            outputAudio = "[amixed]";
        }

        cmd.addAll(List.of("-filter_complex", filter,
                "-map", "0:v:0", "-map", outputAudio,
                "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                "-t", trimNum(videoDur),
                dst.toString()));
        ProcRunner.Result r = runTask(cmd, 900, context);
        context.throwIfCancelled();
        if (!r.ok()) log.warn("muxAudio failed: {}", tail(r.out()));
        return r.ok();
    }

    /** Preserves normalized source audio and optionally adds a looped BGM track. */
    public boolean muxOriginalAudio(Path video, String bgm, double originalVolume, double bgmVolume,
                                    double videoDur, Path dst) {
        return muxOriginalAudio(video, bgm, originalVolume, bgmVolume, videoDur, dst,
                ProcessRegistry.CancellationContext.none());
    }

    public boolean muxOriginalAudio(Path video, String bgm, double originalVolume, double bgmVolume,
                                    double videoDur, Path dst, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        List<String> cmd = new ArrayList<>(List.of(props.getFfmpeg(), "-y", "-i", video.toString()));
        boolean hasBgm = bgm != null && !bgm.isBlank();
        if (!hasBgm) {
            cmd.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?", "-c:v", "copy", "-c:a", "aac",
                    "-t", trimNum(videoDur), dst.toString()));
            return runTask(cmd, 900, context).ok();
        }
        cmd.addAll(List.of("-stream_loop", "-1", "-i", bgm));
        double fadeOutStart = Math.max(0, videoDur - 2);
        String filter = String.format(
                "[0:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,volume=%s,atrim=0:%s[a0];" +
                        "[1:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,volume=%s,afade=t=in:st=0:d=1,afade=t=out:st=%s:d=2,atrim=0:%s[a1];" +
                        "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0,loudnorm=I=-14:TP=-1.5:LRA=11[aout]",
                trimNum(originalVolume), trimNum(videoDur), trimNum(bgmVolume), trimNum(fadeOutStart), trimNum(videoDur));
        cmd.addAll(List.of("-filter_complex", filter, "-map", "0:v:0", "-map", "[aout]", "-c:v", "copy", "-c:a", "aac",
                "-b:a", "192k", "-t", trimNum(videoDur), dst.toString()));
        ProcRunner.Result run = runTask(cmd, 900, context);
        context.throwIfCancelled();
        if (!run.ok()) log.warn("muxOriginalAudio failed: {}", tail(run.out()));
        return run.ok();
    }

    @Data
    public static class Caption {
        private String text;
        private double from;
        private double to;
    }

    /** Builds a bounded set of controlled drawtext filters for ASR-mapped captions. */
    public String captionsFilter(List<Caption> captions, String fontFile, int fontSize, String color) {
        List<String> filters = new ArrayList<>();
        for (Caption caption : captions) {
            if (caption == null || caption.getText() == null || caption.getText().isBlank() || caption.getTo() <= caption.getFrom()) continue;
            filters.add(hookTextFilter(caption.getText(), fontFile, fontSize, color, caption.getFrom(), caption.getTo()).replace(":y=h*0.16", ":y=h*0.72"));
            if (filters.size() >= 80) break;
        }
        return String.join(",", filters);
    }

    /**
     * Builds a bounded set of chunked, safe-region drawtext filters. Long captions are split into
     * at most {@code lineMaxChars} characters per line and stacked within the lower safe region;
     * {@code baseYFraction} is the bottom line's vertical fraction of frame height (default 0.72).
     */
    public String captionsFilter(List<Caption> captions, String fontFile, int fontSize, String color,
                                 int lineMaxChars, double baseYFraction) {
        List<String> filters = new ArrayList<>();
        int maxChars = Math.max(1, lineMaxChars);
        for (Caption caption : captions) {
            if (caption == null || caption.getText() == null || caption.getText().isBlank() || caption.getTo() <= caption.getFrom()) continue;
            List<String> lines = chunkCaptionText(caption.getText(), maxChars);
            for (int i = 0; i < lines.size(); i++) {
                double y = safeCaptionY(i, lines.size(), baseYFraction);
                filters.add(hookTextFilter(lines.get(i), fontFile, fontSize, color,
                        caption.getFrom(), caption.getTo(), "h*" + yFraction(y)));
                if (filters.size() >= 80) break;
            }
            if (filters.size() >= 80) break;
        }
        return String.join(",", filters);
    }

    /** Split text into chunks of at most {@code maxChars} code points, preserving surrogate pairs. */
    static List<String> chunkCaptionText(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        int limit = Math.max(1, maxChars);
        StringBuilder current = new StringBuilder();
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (count >= limit) {
                out.add(current.toString());
                current.setLength(0);
                count = 0;
            }
            current.appendCodePoint(codePoint);
            count++;
            i += Character.charCount(codePoint);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    /** Compute a clamped safe-region y fraction for a stacked caption line. */
    static double safeCaptionY(int lineIndex, int totalLines, double baseYFraction) {
        if (totalLines <= 1) return clampCaptionY(baseYFraction);
        double lineHeight = 0.075;
        double y = baseYFraction - (totalLines - 1 - lineIndex) * lineHeight;
        return clampCaptionY(y);
    }

    private static double clampCaptionY(double y) {
        return Math.max(0.50, Math.min(0.85, y));
    }

    private static String yFraction(double y) {
        String s = String.format(java.util.Locale.US, "%.2f", y);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s += "0";
        return s;
    }

    /** 构造受控的 drawtext 过滤器，供拼接阶段合并字幕使用。 */
    public String hookTextFilter(String text, String fontFile, int fontSize,
                                 String color, double from, double to) {
        return hookTextFilter(text, fontFile, fontSize, color, from, to, "h*0.16");
    }

    /** 构造受控的 drawtext 过滤器，允许显式指定纵向位置（如 h*0.70）。 */
    public String hookTextFilter(String text, String fontFile, int fontSize,
                                 String color, double from, double to, String yExpr) {
        String safe = text == null ? "" : text
                .replace("\\", "\\\\").replace(":", "\\:")
                .replace("'", "\u2019").replace("%", "\\%")
                .replace("\n", " ");
        StringBuilder df = new StringBuilder("drawtext=");
        if (fontFile != null && !fontFile.isBlank()) {
            df.append("fontfile='").append(fontFile.replace("\\", "/").replace(":", "\\:")).append("':");
        }
        return df.append("text='").append(safe).append("'")
                .append(":fontcolor=").append(color)
                .append(":fontsize=").append(fontSize)
                .append(":borderw=6:bordercolor=black@0.65")
                .append(":x=(w-text_w)/2:y=").append(yExpr)
                .append(":enable='between(t,").append(trimNum(from)).append(",").append(trimNum(to)).append(")'")
                .toString();
    }

    /** Builds a conservative lower-band cover for old embedded subtitles before new captions are burned. */
    public String sourceSubtitleSafeBandFilter() {
        return "drawbox=x=0:y=ih*0.68:w=iw:h=ih*0.22:color=black@0.58:t=fill";
    }

    /** 烧字幕（drawtext，硬字幕；钩子文案通常需要压在开头 3 秒）。 */
    public boolean burnText(Path video, String text, String fontFile, int fontSize,
                            String color, double from, double to, Path dst) {
        return burnText(video, text, fontFile, fontSize, color, from, to, dst,
                ProcessRegistry.CancellationContext.none());
    }

    public boolean burnText(Path video, String text, String fontFile, int fontSize,
                            String color, double from, double to, Path dst,
                            ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        List<String> cmd = List.of(props.getFfmpeg(), "-y", "-i", video.toString(),
                "-vf", hookTextFilter(text, fontFile, fontSize, color, from, to),
                "-c:v", "libx264", "-preset", "medium", "-crf", "18",
                "-c:a", "copy", dst.toString());
        ProcRunner.Result r = runTask(cmd, 900, context);
        context.throwIfCancelled();
        if (!r.ok()) log.warn("burnText failed: {}", tail(r.out()));
        return r.ok();
    }

    /** 抽缩略图 */
    public boolean thumbnail(String src, Path dst, double at) {
        return thumbnail(src, dst, at, ProcessRegistry.CancellationContext.none());
    }

    public boolean thumbnail(String src, Path dst, double at, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        List<String> cmd = List.of(props.getFfmpeg(), "-y",
                "-ss", trimNum(at), "-i", src,
                "-frames:v", "1", "-vf", "scale=360:-2", dst.toString());
        context.throwIfCancelled();
        boolean ok = runTask(cmd, 60, context).ok();
        context.throwIfCancelled();
        return ok;
    }

    /** 抽取分析缓存帧；与素材列表缩略图共用受控的 ffmpeg 调用和超时边界。 */
    public boolean analysisFrame(String src, Path dst, double at) {
        return analysisFrame(src, dst, at, ProcessRegistry.CancellationContext.none());
    }

    public boolean analysisFrame(String src, Path dst, double at,
                                 ProcessRegistry.CancellationContext context) {
        return thumbnail(src, dst, Math.max(0, at), context);
    }

    public static String trimNum(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.format(java.util.Locale.US, "%.3f", d);
    }

    private ProcRunner.Result runTask(List<String> command, long timeoutSec,
                                      ProcessRegistry.CancellationContext context) {
        return taskRunner == null ? runner.run(command, timeoutSec) : taskRunner.run(command, timeoutSec, context);
    }

    private ProcRunner.SeparateResult runTaskSeparate(List<String> command, long timeoutSec,
                                                       ProcessRegistry.CancellationContext context) {
        return taskRunner == null ? runner.runSeparate(command, timeoutSec) : taskRunner.runSeparate(command, timeoutSec, context);
    }

    private String tail(String s) {
        if (s == null) return "";
        return s.length() <= 1200 ? s : s.substring(s.length() - 1200);
    }
}
