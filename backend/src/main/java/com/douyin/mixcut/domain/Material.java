package com.douyin.mixcut.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 素材：视频 / 音频 / 图片。
 * role 决定它在混剪中的用途（钩子、主体、产品、明星、人声、BGM、结尾卡片）。
 */
@Data
@TableName("material")
public class Material {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long folderId;
    private String name;
    private String filePath;

    private FileType fileType = FileType.video;

    private MaterialRole role = MaterialRole.none;

    private Double durationSec;
    private Integer width;
    private Integer height;
    private String thumbnail;
    private String tags;

    /** 浏览器使用的稳定资源地址，不暴露本机绝对路径。 */
    @TableField(exist = false)
    private String previewUrl;
    @TableField(exist = false)
    private String thumbnailUrl;

    private Source source = Source.local;

    private String sourceUrl;

    private Status status = Status.ready;

    /** User-explicit flag: mute the original audio track of this material during render. */
    private Boolean muteOriginalAudio = false;

    /** User-explicit flag: transcribe this material's audio for subtitle generation. */
    private Boolean transcribeForSubtitles = false;

    private LocalDateTime createdAt;

    public enum FileType { video, audio, image }
    public enum Source { local, crawl, generated }
    public enum Status { ready, processing, failed }
}
