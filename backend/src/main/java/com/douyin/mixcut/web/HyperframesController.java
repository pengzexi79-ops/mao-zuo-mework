package com.douyin.mixcut.web;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional local-only Hyperframes module. It never blocks the FFmpeg pipeline. */
@RestController
@RequestMapping("/api/hyperframes")
@RequiredArgsConstructor
public class HyperframesController {

    private static final Path MODULE = Path.of("..", "tools", "hyperframes-upstream")
            .toAbsolutePath().normalize();

    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("installed", Files.isDirectory(MODULE));
        result.put("enabled", false);
        result.put("mode", "local-optional");
        result.put("modulePath", MODULE.toString());
        result.put("message", Files.isDirectory(MODULE)
                ? "Hyperframes 模块已就绪；默认关闭，启用后生成的本地 MP4 可作为片头素材。"
                : "未安装 Hyperframes；普通 FFmpeg 混剪不受影响。");
        return R.ok(result);
    }
}
