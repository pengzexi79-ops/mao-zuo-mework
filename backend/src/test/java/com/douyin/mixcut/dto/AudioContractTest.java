package com.douyin.mixcut.dto;

import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.service.AudioContractService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioContractTest {
    @Test
    void mapsMeasuredAudioFactsAndSilenceRatio() {
        FfmpegTool.MediaInfo media = new FfmpegTool.MediaInfo();
        media.setAudioSampleRate(44100);
        media.setAudioChannels(2);
        media.setAudioCodec("aac");
        media.setAudioDuration(10);
        media.setAudioStartSec(0.1);
        FfmpegTool.AudioQuality quality = new FfmpegTool.AudioQuality();
        quality.setMeanVolumeDb(-16.0);
        quality.setMaxSilenceSec(2.0);

        AudioContract contract = AudioContract.from(media, quality, 10, "tts");

        assertEquals(44100, contract.getSampleRate());
        assertEquals(2, contract.getChannels());
        assertEquals("aac", contract.getCodec());
        assertEquals(10, contract.getOutputDuration(), 0.001);
        assertEquals(0.2, contract.getSilenceRatio(), 0.001);
        assertEquals("tts", contract.getSourceType());
    }

    @Test
    void rejectsInvalidMeasuredFactsWithStableCodes() {
        AudioContract contract = new AudioContract();
        contract.setOutputDuration(2);
        contract.setStartSec(0);
        contract.setEndSec(2);
        contract.setSilenceRatio(0.8);

        AudioContractService service = new AudioContractService(null, new com.douyin.mixcut.config.AppProps());
        var errors = service.validate(contract, 2);

        assertTrue(errors.contains("AUDIO_SAMPLE_RATE_INVALID"));
        assertTrue(errors.contains("AUDIO_CHANNELS_INVALID"));
        assertTrue(errors.contains("AUDIO_CODEC_MISSING"));
        assertTrue(errors.contains("AUDIO_SILENCE_EXCESSIVE"));
    }
}
