package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.dto.AudioContract;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcessRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Builds and validates measured audio facts shared by rendering and delivery checks. */
@Service
@RequiredArgsConstructor
public class AudioContractService {
    private final FfmpegTool ffmpeg;
    private final AppProps props;

    public AudioContract contract(FfmpegTool.MediaInfo media, FfmpegTool.AudioQuality quality,
                                  double inputDuration, String sourceType) {
        return AudioContract.from(media, quality, inputDuration, sourceType);
    }

    public AudioContract inspect(String path, double inputDuration, String sourceType,
                                 ProcessRegistry.CancellationContext context) {
        ProcessRegistry.CancellationContext effective = context == null
                ? ProcessRegistry.CancellationContext.none() : context;
        effective.throwIfCancelled();
        FfmpegTool.MediaInfo media = ffmpeg.probe(path, effective);
        effective.throwIfCancelled();
        FfmpegTool.AudioQuality quality = ffmpeg.audioQuality(java.nio.file.Path.of(path), effective);
        effective.throwIfCancelled();
        return AudioContract.from(media, quality, inputDuration, sourceType);
    }

    public List<String> validate(AudioContract contract, double requiredDuration) {
        List<String> errors = new ArrayList<>();
        if (contract == null) return List.of("AUDIO_CONTRACT_MISSING");
        if (!contract.isHasAudio()) errors.add("AUDIO_STREAM_MISSING");
        if (!contract.isReadable()) errors.add("AUDIO_NOT_READABLE");
        if (contract.getOutputDuration() <= 0) errors.add("AUDIO_DURATION_INVALID");
        if (contract.getSampleRate() == null || contract.getSampleRate() <= 0) errors.add("AUDIO_SAMPLE_RATE_INVALID");
        if (contract.getChannels() == null || contract.getChannels() <= 0) errors.add("AUDIO_CHANNELS_INVALID");
        if (contract.getCodec() == null || contract.getCodec().isBlank()) errors.add("AUDIO_CODEC_MISSING");
        if (requiredDuration > 0 && Math.abs(contract.getOutputDuration() - requiredDuration) > durationTolerance(requiredDuration)) {
            errors.add("AUDIO_DURATION_MISMATCH");
        }
        if (Math.abs(contract.getEndSec() - contract.getStartSec()) <= 0) errors.add("AUDIO_TIMELINE_INVALID");
        if (contract.getSilenceRatio() > silenceRatioLimit()) errors.add("AUDIO_SILENCE_EXCESSIVE");
        return errors;
    }

    private double durationTolerance(double duration) {
        return Math.max(0.35, duration * 0.08);
    }

    private double silenceRatioLimit() {
        return 0.5;
    }
}
