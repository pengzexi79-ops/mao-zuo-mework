package com.douyin.mixcut.web;

import com.douyin.mixcut.dto.UnifiedTask;
import com.douyin.mixcut.service.TaskQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskQueryService service;

    @GetMapping
    public R<List<UnifiedTask>> list(@RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) String source,
                                     @RequestParam(required = false) String status) {
        return R.ok(service.list(limit, source, status));
    }
}
