package com.douyin.mixcut.web;

import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.service.MaterialGapService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Material gap analysis and public-source auto-fill.
 *
 * <p>Two endpoints:
 * <ul>
 *   <li>POST /api/material/gap — analyze what's missing</li>
 *   <li>POST /api/material/auto-fill — queue public-source crawl jobs</li>
 * </ul>
 *
 * <p>Auto-fill only touches Wikimedia Commons, Internet Archive (visual),
 * and Mixkit (audio). All fetching goes through the existing
 * CrawlJobService import queue. No direct uncontrolled downloading.
 */
@RestController
@RequestMapping("/api/material")
@RequiredArgsConstructor
public class MaterialGapController {

    private final MaterialGapService gapService;

    /** Request body for gap analysis. */
    @Data
    public static class GapRequest {
        private Long projectId;
        private MixParams params;
    }

    /**
     * Analyze the material gap for a given project and mix params.
     * Returns structured info: available seconds, missing roles, project keyword,
     * and which public sources can be used to fill the gap.
     */
    @PostMapping("/gap")
    public R<MaterialGapService.MaterialGapResult> gap(@RequestBody GapRequest req) {
        return R.ok(gapService.analyze(
                req == null ? null : req.getProjectId(),
                req == null ? null : req.getParams()));
    }

    /**
     * Queue auto-fill crawl jobs from public, no-login sources only.
     * Returns the list of created CrawlJob IDs so the frontend can poll their status.
     */
    @PostMapping("/auto-fill")
    public R<MaterialGapService.AutoFillResult> autoFill(@RequestBody MaterialGapService.AutoFillRequest req) {
        return R.ok(gapService.autoFill(req == null ? new MaterialGapService.AutoFillRequest() : req));
    }
}
