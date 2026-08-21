package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfmpegToolTest {

    @Test
    void cutNormalizeProducesReadableVerticalClip() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        assumeTrue(runner.available(props.getFfprobe(), "-version"), "ffprobe unavailable; integration test skipped");

        Path work = Files.createTempDirectory("mixcut-ffmpeg-test-");
        Path source = work.resolve("source.mp4");
        Path clip = work.resolve("clip.mp4");
        ProcRunner.Result generated = runner.run(List.of(
                props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=size=640x360:rate=30", "-t", "4", "-an",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", source.toString()), 120);
        assertTrue(generated.ok(), generated.out());

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.cutNormalize(source.toString(), 1.0, 1.5, 360, 640, 30, clip), "cutNormalize failed");
        assertTrue(Files.size(clip) > 1024);

        FfmpegTool.MediaInfo info = tool.probe(clip.toString());
        assertTrue(info.isHasVideo());
        assertEquals(360, info.getWidth());
        assertEquals(640, info.getHeight());
        assertTrue(info.getDuration() > 1.0 && info.getDuration() < 2.0, "duration=" + info.getDuration());
    }

    @Test
    void imageToClipKeepsRequestedDuration() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-image-test-");
        Path image = work.resolve("source.png");
        Path clip = work.resolve("clip.mp4");
        ProcRunner.Result generated = runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=size=360x640:rate=1", "-frames:v", "1", image.toString()), 120);
        assertTrue(generated.ok(), generated.out());

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.imageToClip(image.toString(), 2.0, 360, 640, 30, clip));
        double duration = tool.probe(clip.toString()).getDuration();
        assertTrue(duration >= 1.9 && duration <= 2.3, "duration=" + duration);
    }

    @Test
    void muxesHookOnlyAudioInsideVideoDuration() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-hook-audio-test-");
        Path video = work.resolve("video.mp4");
        Path hook = work.resolve("hook.wav");
        Path output = work.resolve("output.mp4");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "color=c=black:s=360x640:r=30:d=3", "-an", "-c:v", "libx264", video.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=44100:duration=1", hook.toString()), 120).ok());

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.muxAudio(video, null, null, 0.22, hook.toString(), 1.0, 2.0, 1.0, 3.0, output));
        FfmpegTool.MediaInfo info = tool.probe(output.toString());
        assertTrue(info.isHasVideo());
        assertTrue(info.isHasAudio());
        assertTrue(info.getDuration() > 2.8 && info.getDuration() < 3.2, "duration=" + info.getDuration());
    }

    @Test
    void mixesShortVoiceWithLoopedBgmToFullVideoDuration() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-voice-bgm-test-");
        Path video = work.resolve("video.mp4");
        Path voice = work.resolve("voice.wav");
        Path bgm = work.resolve("bgm.wav");
        Path output = work.resolve("output.mp4");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=size=360x640:rate=30", "-t", "5", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100:duration=2", voice.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=44100:duration=1", bgm.toString()), 120).ok());

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.muxAudio(video, voice.toString(), bgm.toString(), 0.22, 5.0, output));
        FfmpegTool.MediaInfo info = tool.probe(output.toString());
        assertTrue(info.isHasAudio());
        assertTrue(info.getAudioDuration() >= 4.7 && info.getAudioDuration() <= 5.3,
                "audio duration=" + info.getAudioDuration());
        assertTrue(info.getDuration() >= 4.7 && info.getDuration() <= 5.3,
                "container duration=" + info.getDuration());
    }

    @Test
    void ducksBgmWithNarrationWithoutReusingFilterPads() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-ducked-audio-test-");
        Path video = work.resolve("video.mp4");
        Path voice = work.resolve("voice.wav");
        Path bgm = work.resolve("bgm.wav");
        Path output = work.resolve("output.mp4");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=size=360x640:rate=30", "-t", "5", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100:duration=5", voice.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=44100:duration=1", bgm.toString()), 120).ok());

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.muxAudio(video, voice.toString(), bgm.toString(), 0.22, true,
                null, 0, 0, 1.0, 5.0, output));
        FfmpegTool.MediaInfo info = tool.probe(output.toString());
        assertTrue(info.isHasAudio());
        assertTrue(info.getAudioDuration() >= 4.7 && info.getAudioDuration() <= 5.3,
                "audio duration=" + info.getAudioDuration());
    }

    @Test
    void originalAudioMixKeepsSourceAudioAndVideoDuration() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-original-audio-test-");
        Path video = work.resolve("video.mp4");
        Path bgm = work.resolve("bgm.wav");
        Path output = work.resolve("output.mp4");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=size=360x640:rate=30", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100", "-t", "3", "-shortest",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", video.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=44100:duration=2", bgm.toString()), 120).ok());
        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.muxOriginalAudio(video, bgm.toString(), 0.28, 0.15, 3.0, output));
        FfmpegTool.MediaInfo info = tool.probe(output.toString());
        assertTrue(info.isHasVideo());
        assertTrue(info.isHasAudio());
        assertTrue(info.getAudioDuration() > 2.7 && info.getAudioDuration() < 3.3);
    }

    @Test
    void captionsFilterIsBoundedAndUsesLowerCaptionPosition() {
        AppProps props = new AppProps();
        FfmpegTool tool = new FfmpegTool(props, new ProcRunner());
        FfmpegTool.Caption caption = new FfmpegTool.Caption();
        caption.setText("中文口播");
        caption.setFrom(1.0);
        caption.setTo(2.5);
        String filter = tool.captionsFilter(List.of(caption), "", 32, "white");
        assertTrue(filter.contains("between(t,1,2.500)"));
        assertTrue(filter.contains("y=h*0.72"));
    }

    @Test
    void audioQualityDistinguishesSignalFromSilence() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-audio-quality-test-");
        Path signal = work.resolve("signal.wav");
        Path silence = work.resolve("silence.wav");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=44100:duration=2", signal.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "anullsrc=channel_layout=stereo:sample_rate=44100", "-t", "2", silence.toString()), 120).ok());

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.audioQuality(signal).isReadable());
        assertTrue(tool.audioQuality(signal).getMaxSilenceSec() < 0.5);
        assertTrue(tool.audioQuality(silence).isReadable());
        assertTrue(tool.audioQuality(silence).getMaxSilenceSec() > 1.5);
    }

    @Test
    void videoQualityDetectsBlackFrames() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-video-quality-test-");
        Path black = work.resolve("black.mp4");
        Path normal = work.resolve("normal.mp4");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "color=c=black:s=360x640:r=30:d=2", "-an", "-c:v", "libx264", black.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=size=360x640:rate=30", "-t", "2", "-an", "-c:v", "libx264", normal.toString()), 120).ok());

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.videoQuality(black).isReadable());
        assertTrue(tool.videoQuality(black).getBlackSec() > 1.5);
        assertTrue(tool.videoQuality(normal).isReadable());
        assertTrue(tool.videoQuality(normal).getBlackSec() < 0.1);
    }

    @Test
    void videoQualityDetectsSolidRedMagentaErrorFrames() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-red-magenta-test-");
        Path red = work.resolve("red.mp4");
        Path magenta = work.resolve("magenta.mp4");
        Path normal = work.resolve("normal.mp4");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "color=c=red:s=360x640:r=30:d=2", "-an", "-c:v", "libx264", red.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "color=c=magenta:s=360x640:r=30:d=2", "-an", "-c:v", "libx264", magenta.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=size=360x640:rate=30", "-t", "2", "-an", "-c:v", "libx264", normal.toString()), 120).ok());

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.videoQuality(red).getRedMagentaSec() > 1.5, "solid red must be detected");
        assertTrue(tool.videoQuality(magenta).getRedMagentaSec() > 1.5, "solid magenta must be detected");
        assertTrue(tool.videoQuality(normal).getRedMagentaSec() < 0.1, "normal test source must not be flagged");
    }

    @Test
    void audioQualityMeasuresMeanVolume() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-audio-volume-test-");
        Path tone = work.resolve("tone.wav");
        Path silence = work.resolve("silence.wav");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=44100:duration=2", tone.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "anullsrc=channel_layout=stereo:sample_rate=44100", "-t", "2", silence.toString()), 120).ok());

        FfmpegTool tool = new FfmpegTool(props, runner);
        FfmpegTool.AudioQuality toneQuality = tool.audioQuality(tone);
        FfmpegTool.AudioQuality silenceQuality = tool.audioQuality(silence);
        assertTrue(toneQuality.isReadable());
        assertNotNull(toneQuality.getMeanVolumeDb());
        assertTrue(toneQuality.getMeanVolumeDb() > -25, "tone mean volume=" + toneQuality.getMeanVolumeDb());
        assertTrue(silenceQuality.isReadable());
        assertNotNull(silenceQuality.getMeanVolumeDb());
        assertTrue(silenceQuality.getMeanVolumeDb() < -50, "silence mean volume=" + silenceQuality.getMeanVolumeDb());
    }

    @Test
    void videoQualityDetectsFrozenAndSolidColorContent() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        Path work = Files.createTempDirectory("mixcut-frozen-solid-test-");
        Path color = work.resolve("color.mp4");
        Path dynamic = work.resolve("dynamic.mp4");
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "color=c=blue:s=360x640:r=30:d=2", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", color.toString()), 120).ok());
        assertTrue(runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=size=360x640:rate=30", "-t", "2", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", dynamic.toString()), 120).ok());

        FfmpegTool tool = new FfmpegTool(props, runner);
        FfmpegTool.VideoQuality colorQuality = tool.videoQuality(color);
        FfmpegTool.VideoQuality dynamicQuality = tool.videoQuality(dynamic);
        assertTrue(colorQuality.isReadable());
        assertTrue(colorQuality.getFrozenSec() > 1.5, "solid color clip must register as frozen, frozen=" + colorQuality.getFrozenSec());
        assertTrue(colorQuality.getSolidColorSec() > 1.5, "solid color clip must register as solid color, solid=" + colorQuality.getSolidColorSec());
        assertTrue(dynamicQuality.isReadable());
        assertTrue(dynamicQuality.getFrozenSec() < 0.3, "moving testsrc must not be frozen, frozen=" + dynamicQuality.getFrozenSec());
        assertTrue(dynamicQuality.getSolidColorSec() < 0.3, "moving testsrc must not be solid color, solid=" + dynamicQuality.getSolidColorSec());
    }

    @Test
    void concatResetsTimelineAndKeepsExpectedDuration() throws Exception {
        AppProps props = new AppProps();
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");

        Path work = Files.createTempDirectory("mixcut-concat-test-");
        Path first = work.resolve("first.mp4");
        Path second = work.resolve("second.mp4");
        Path list = work.resolve("list.txt");
        Path output = work.resolve("output.mp4");
        for (Path clip : List.of(first, second)) {
            ProcRunner.Result generated = runner.run(List.of(props.getFfmpeg(), "-y", "-f", "lavfi", "-i",
                    "color=c=blue:s=360x640:r=30:d=1.2", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    clip.toString()), 120);
            assertTrue(generated.ok(), generated.out());
        }
        Files.writeString(list, "file '" + first.toString().replace("\\", "/") + "'\n"
                + "file '" + second.toString().replace("\\", "/") + "'\n");

        FfmpegTool tool = new FfmpegTool(props, runner);
        assertTrue(tool.concat(list, output));
        FfmpegTool.MediaInfo info = tool.probe(output.toString());
        assertTrue(info.isHasVideo());
        assertTrue(info.getDuration() > 2.0 && info.getDuration() < 3.0, "unexpected concat duration=" + info.getDuration());
    }
}
