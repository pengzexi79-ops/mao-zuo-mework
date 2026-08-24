package com.douyin.mixcut.dto;

import com.douyin.mixcut.external.FfmpegTool;
import lombok.Data;

/** Provider-neutral facts measured from one audio-bearing media output. */
@Data
public class AudioContract {
    private boolean readable;
    private boolean hasAudio;
    private Integer sampleRate;
    private Integer channels;
    private String codec;
    private double inputDuration;
    private double outputDuration;
    private double startSec;
    private double endSec;
    private Double loudness;
    private double silenceRatio;
    private String sourceType;

    public static AudioContract from(FfmpegTool.MediaInfo media, FfmpegTool.AudioQuality quality,
                                     double inputDuration, String sourceType) {
        AudioContract contract = new AudioContract();
        contract.readable = quality != null && quality.isReadable();
        if (media != null) {
            contract.hasAudio = media.isHasAudio();
            contract.sampleRate = media.getAudioSampleRate();
            contract.channels = media.getAudioChannels();
            contract.codec = media.getAudioCodec();
            contract.outputDuration = Math.max(0, media.getAudioDuration() > 0 ? media.getAudioDuration() : media.getDuration());
            contract.startSec = media.getAudioStartSec();
        }
        contract.inputDuration = Math.max(0, inputDuration);
        contract.endSec = contract.startSec + contract.outputDuration;
        contract.loudness = quality == null ? null : quality.getMeanVolumeDb();
        double silence = quality == null ? 0 : Math.max(0, quality.getMaxSilenceSec());
        contract.silenceRatio = contract.outputDuration <= 0 ? 0 : Math.min(1, silence / contract.outputDuration);
        contract.sourceType = sourceType == null || sourceType.isBlank() ? "unknown" : sourceType;
        return contract;
    }
}
