package com.douyin.mixcut.web;

import com.douyin.mixcut.service.MediaGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-generation")
@RequiredArgsConstructor
public class MediaGenerationController {
    private final MediaGenerationService service;

    @GetMapping("/providers")
    public R<List<Map<String, Object>>> providers() { return R.ok(service.imageProviders()); }

    @GetMapping("/image/providers")
    public R<List<Map<String, Object>>> imageProviders() { return providers(); }

    @PostMapping("/image")
    public R<MediaGenerationService.Task> image(@RequestBody MediaGenerationService.ImageRequest request) { return R.ok(service.image(request)); }

    @PostMapping("/video")
    public R<MediaGenerationService.Task> video(@RequestBody MediaGenerationService.VideoRequest request) { return R.ok(service.video(request)); }

    @PostMapping("/voice")
    public R<MediaGenerationService.Task> voice(@RequestBody MediaGenerationService.VoiceRequest request) { return R.ok(service.voice(request)); }

    @GetMapping("/tasks")
    public R<List<MediaGenerationService.Task>> tasks() { return R.ok(service.recent()); }

    @GetMapping("/tasks/{id}")
    public R<MediaGenerationService.Task> task(@PathVariable String id) { return R.ok(service.get(id)); }

    @GetMapping("/capabilities")
    public R<Map<String, Object>> capabilities() {
        return R.ok(Map.of(
                "image", true,
                "video", true,
                "audio", true,
                "message", "已接入 OpenAI-compatible 图片、异步视频和配音接口。每个供应商必须实际支持对应模型；任务会显示认证、额度、模型或接口不兼容等失败诊断。"));
    }
}
