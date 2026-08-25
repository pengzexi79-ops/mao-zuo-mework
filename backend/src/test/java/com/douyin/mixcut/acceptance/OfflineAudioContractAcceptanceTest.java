package com.douyin.mixcut.acceptance;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.service.AudioContractService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfflineAudioContractAcceptanceTest {
    @TempDir Path tempDir;

    @Test
    void validatesReadableBgmAndRejectsSilentAudioUsingMeasuredContract() throws Exception {
        Path projectRoot = OfflineAcceptanceSupport.projectRoot();
        AppProps props = OfflineAcceptanceSupport.props(tempDir, projectRoot);
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "bundled ffmpeg is unavailable");
        assumeTrue(runner.available(props.getFfprobe(), "-version"), "bundled ffprobe is unavailable");
        AudioContractService contracts = new AudioContractService(new FfmpegTool(props, runner), props);

        var bgm = contracts.inspect(OfflineAcceptanceSupport.copyFixture("audio_bgm", tempDir).toString(), 0,
                "fixture-bgm", null);
        var silence = contracts.inspect(OfflineAcceptanceSupport.copyFixture("audio_silence", tempDir).toString(), 0,
                "fixture-silence", null);

        assertTrue(bgm.isReadable());
        assertTrue(bgm.isHasAudio());
        assertTrue(bgm.getSampleRate() > 0);
        assertTrue(bgm.getChannels() > 0);
        assertTrue(bgm.getCodec() != null && !bgm.getCodec().isBlank());
        assertFalse(contracts.validate(bgm, 0).contains("AUDIO_SILENCE_EXCESSIVE"));
        assertTrue(contracts.validate(silence, 0).contains("AUDIO_SILENCE_EXCESSIVE"));
    }
}
