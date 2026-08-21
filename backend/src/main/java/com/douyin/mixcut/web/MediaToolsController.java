package com.douyin.mixcut.web;

import com.douyin.mixcut.service.MediaToolsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/media-tools")
@RequiredArgsConstructor
public class MediaToolsController {
    private final MediaToolsService service;

    @PostMapping("/image")
    public R<MediaToolsService.Task> image(@RequestBody MediaToolsService.ImageRequest request) {
        return R.ok(service.image(request));
    }

    @PostMapping("/audio-separate")
    public R<MediaToolsService.Task> separate(@RequestBody(required = false) IdRequest request) {
        return R.ok(service.separate(request == null ? null : request.getMaterialId()));
    }

    @PostMapping("/video-split")
    public R<MediaToolsService.Task> split(@RequestBody(required = false) SplitRequest request) {
        return R.ok(service.split(request == null ? null : request.getMaterialId(), request == null || request.getClipSec() == null ? 3 : request.getClipSec()));
    }

    @PostMapping("/timeline")
    public R<MediaToolsService.Task> timeline(@RequestBody MediaToolsService.TimelineRequest request) {
        return R.ok(service.timeline(request));
    }

    @PostMapping("/subtitle-cover")
    public R<MediaToolsService.Task> cover(@RequestBody MediaToolsService.CoverRequest request) {
        return R.ok(service.cover(request));
    }

    @PostMapping("/auto-trim")
    public R<MediaToolsService.Task> trimSilence(@RequestBody(required = false) IdRequest request) {
        return R.ok(service.trimSilence(request == null ? null : request.getMaterialId()));
    }

    @PostMapping("/open-output-directory")
    public R<Void> openOutputDirectory() {
        service.openOutputDirectory();
        return R.ok();
    }

    @GetMapping("/tasks")
    public R<List<MediaToolsService.Task>> recent() {
        return R.ok(service.recent());
    }

    @GetMapping("/tasks/{id}")
    public R<MediaToolsService.Task> task(@PathVariable String id) {
        return R.ok(service.get(id));
    }

    @lombok.Data
    public static class IdRequest {
        private Long materialId;
    }

    @lombok.Data
    public static class SplitRequest {
        private Long materialId;
        private Double clipSec;
    }
}
