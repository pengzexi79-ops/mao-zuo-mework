package com.douyin.mixcut.web;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.CrawlJob;
import com.douyin.mixcut.domain.JobStatus;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.service.CrawlJobService;
import com.douyin.mixcut.service.MaterialGapService;
import com.douyin.mixcut.service.MixPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialGapControllerTest {

    @Mock private MaterialStore materialRepo;
    @Mock private ProjectRepo projectRepo;
    @Mock private MaterialFolderRepo folderRepo;
    @Mock private CrawlerGateway crawler;
    @Mock private CrawlJobService crawlJobService;

    private MaterialGapService gapService;

    @BeforeEach
    void setUp() {
        gapService = new MaterialGapService(materialRepo, projectRepo, folderRepo, new MixPlanner(), crawler, crawlJobService, new AppProps());
    }

    // ---------------------------------------------------------------
    //  Gap Analysis Tests
    // ---------------------------------------------------------------

    @Test
    void gapResultReturnsSufficientFalseWhenNoVisualMaterials() {
        when(materialRepo.findAll()).thenReturn(List.of());

        MaterialGapService.MaterialGapResult result = gapService.analyze(null, null);

        assertFalse(result.isSufficient());
        assertEquals(0, result.getAvailableVisualSec());
        assertTrue(result.getNotes().stream().anyMatch(n -> n.contains("尚未导入")));
        assertTrue(result.getMissingRoles().contains("body"));
    }

    @Test
    void gapResultShowsMissingRolesWhenPoolIsEmpty() {
        // Materials exist but are all audio
        Material audio = new Material();
        audio.setId(1L); audio.setName("bgm.mp3"); audio.setFilePath("/tmp/bgm.mp3");
        audio.setFileType(Material.FileType.audio); audio.setRole(MaterialRole.bgm);
        audio.setDurationSec(30.0); audio.setStatus(Material.Status.ready);

        when(materialRepo.findAll()).thenReturn(List.of(audio));

        MaterialGapService.MaterialGapResult result = gapService.analyze(null, null);

        assertFalse(result.isSufficient());
        assertEquals(0, result.getAvailableVisualSec());
        assertTrue(result.getMissingRoles().contains("body"));
        assertTrue(result.getRoleCounts().get("bgm") >= 1);
    }

    @Test
    void gapResultShowsSufficientWhenVisualsExist() {
        Material visual = createVisual(1L, "clip.mp4", 120.0, MaterialRole.body);

        when(materialRepo.findAll()).thenReturn(List.of(visual));

        MaterialGapService.MaterialGapResult result = gapService.analyze(null, null);

        assertTrue(result.isSufficient());
        assertTrue(result.getAvailableVisualSec() >= 100);
        assertEquals(1, result.getVisualCount());
    }

    @Test
    void gapResultIncludesProjectKeywordWhenProjectExists() {
        Project project = new Project();
        project.setId(1L);
        project.setName("Test Project");
        project.setCategory("beauty");
        project.setProduct("lipstick");

        Material visual = createVisual(1L, "clip.mp4", 30.0, MaterialRole.body);

        when(materialRepo.findAll()).thenReturn(List.of(visual));
        when(projectRepo.findById(1L)).thenReturn(Optional.of(project));

        MaterialGapService.MaterialGapResult result = gapService.analyze(1L, null);

        assertNotNull(result.getProjectKeyword());
        assertTrue(result.getProjectKeyword().contains("beauty")
                || result.getProjectKeyword().contains("lipstick"));
    }

    @Test
    void gapResultReportsRoleCounts() {
        Material body = createVisual(1L, "body.mp4", 30.0, MaterialRole.body);
        Material hook = createVisual(2L, "hook.mp4", 15.0, MaterialRole.hook);
        Material product = createVisual(3L, "prod.mp4", 10.0, MaterialRole.product);
        Material bgm = new Material();
        bgm.setId(4L); bgm.setName("bgm.mp3"); bgm.setFilePath("/tmp/bgm.mp3");
        bgm.setFileType(Material.FileType.audio); bgm.setRole(MaterialRole.bgm);
        bgm.setDurationSec(60.0); bgm.setStatus(Material.Status.ready);

        when(materialRepo.findAll()).thenReturn(List.of(body, hook, product, bgm));

        MaterialGapService.MaterialGapResult result = gapService.analyze(null, null);

        assertEquals(1, (int) result.getRoleCounts().getOrDefault("body", 0));
        assertEquals(1, (int) result.getRoleCounts().getOrDefault("hook", 0));
        assertEquals(1, (int) result.getRoleCounts().getOrDefault("product", 0));
        assertEquals(1, (int) result.getRoleCounts().getOrDefault("bgm", 0));
    }

    @Test
    void gapResultShowsMissingProductWhenRequested() {
        Material body = createVisual(1L, "body.mp4", 120.0, MaterialRole.body);

        when(materialRepo.findAll()).thenReturn(List.of(body));

        MixParams params = new MixParams();
        params.setProductSlots(3);
        params.setEndcard(true);

        MaterialGapService.MaterialGapResult result = gapService.analyze(null, params);

        assertTrue(result.getMissingRoles().contains("product"));
    }

    @Test
    void gapResultRespectsTargetDurationFromParams() {
        Material body = createVisual(1L, "body.mp4", 30.0, MaterialRole.body);

        when(materialRepo.findAll()).thenReturn(List.of(body));

        MixParams params = new MixParams();
        // Set a target that makes sense with defaults: min=50, max=150
        params.setTargetDurationSec(55);

        MaterialGapService.MaterialGapResult result = gapService.analyze(null, params);

        assertEquals(55.0, result.getRequestedTargetSec(), 0.01);
    }

    @Test
    void usablePublicSourcesSeparateNoKeyAutoSourcesFromOfficialAuthorizationSources() {
        when(materialRepo.findAll()).thenReturn(List.of());

        MaterialGapService.MaterialGapResult result = gapService.analyze(null, null);

        List<String> keys = result.getUsablePublicSources().stream()
                .map(m -> String.valueOf(m.get("key"))).toList();
        assertTrue(keys.contains("wikimedia"), "Wikimedia Commons must remain an auto source");
        assertTrue(keys.contains("archive"), "Internet Archive must remain an auto source");
        assertTrue(keys.contains("pexels"), "Pexels should be visible as an official authorization option");
        assertFalse(keys.contains("mixkit"), "Mixkit is manual-import only and must not be offered for auto-fill");
        assertTrue(result.getUsablePublicSources().stream()
                .filter(m -> "wikimedia".equals(m.get("key")) || "archive".equals(m.get("key")))
                .allMatch(m -> "false".equals(String.valueOf(m.get("needKey")))),
                "only Wikimedia and Archive are no-key automatic sources");
        assertEquals("true", result.getUsablePublicSources().stream()
                .filter(m -> "pexels".equals(m.get("key"))).findFirst().orElseThrow().get("needKey"));
    }

    // ---------------------------------------------------------------
    //  Auto-Fill Tests
    // ---------------------------------------------------------------

    @Test
    void autoFillReturnsEmptyResultWhenNoResultsFound() {
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any()))
                .thenReturn(List.of());
        when(crawler.searchPublicVideoQuick(eq("archive"), anyString(), anyInt(), any()))
                .thenReturn(List.of());

        MaterialGapService.AutoFillRequest req = new MaterialGapService.AutoFillRequest();
        req.setSources(List.of("wikimedia", "archive"));
        req.setPerSource(3);

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        assertFalse(result.isAny());
        assertEquals(0, result.getTotalItemsQueued());
        assertEquals(2, result.getSourceResults().size());
        assertTrue(result.getSourceResults().stream()
                .allMatch(r -> "no_results".equals(r.get("status"))));
    }

    @Test
    void autoFillQueuesJobsForFoundItems() {
        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setSource("wikimedia");
        item.setTitle("test-video.mp4");
        item.setType("video");
        item.setDownloadUrl("https://example.com/test.mp4");
        item.setLicense("CC BY-SA 4.0");
        item.setPageUrl("https://commons.wikimedia.org/wiki/File:test");

        CrawlJob mockJob = new CrawlJob();
        mockJob.setId(100L);
        mockJob.setStatus(JobStatus.pending.name());
        mockJob.setName("test job");

        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any()))
                .thenReturn(List.of(item));
        when(crawlJobService.submitVideoItems(anyList(), eq("body")))
                .thenReturn(mockJob);

        MaterialGapService.AutoFillRequest req = new MaterialGapService.AutoFillRequest();
        req.setSources(List.of("wikimedia"));

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        assertTrue(result.isAny());
        assertEquals(1, result.getTotalItemsQueued());
        assertEquals(1, result.getCrawlJobIds().size());
        assertEquals(100L, result.getCrawlJobIds().get(0));
    }

    @Test
    void autoFillRejectsUnsupportedSourceGracefully() {
        MaterialGapService.AutoFillRequest req = new MaterialGapService.AutoFillRequest();
        req.setSources(List.of("freesound", "pixabay"));

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        assertFalse(result.isAny());
        assertTrue(result.getSourceResults().stream()
                .allMatch(r -> "unsupported".equals(r.get("status"))));
    }

    @Test
    void autoFillUsesProjectKeywordWhenProjectPresent() {
        Project project = new Project();
        project.setId(1L);
        project.setCategory("food");
        project.setProduct("noodles");

        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setSource("wikimedia");
        item.setTitle("food noodles cooking.mp4");
        item.setType("video");
        item.setDownloadUrl("https://upload.wikimedia.org/wikipedia/commons/food.mp4");
        item.setLicense("CC BY 4.0");
        item.setLicenseUrl("https://creativecommons.org/licenses/by/4.0/");

        CrawlJob mockJob = new CrawlJob();
        mockJob.setId(200L);

        when(projectRepo.findById(1L)).thenReturn(Optional.of(project));
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any()))
                .thenReturn(List.of(item));
        when(crawlJobService.submitVideoItems(anyList(), eq("body")))
                .thenReturn(mockJob);

        MaterialGapService.AutoFillRequest req = new MaterialGapService.AutoFillRequest();
        req.setProjectId(1L);
        req.setSources(List.of("wikimedia"));

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        assertTrue(result.isAny());
        assertEquals(200L, result.getCrawlJobIds().get(0));
    }

    @Test
    void autoFillUsesCurrentParamsKeywordWhenNoProjectSelected() {
        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setSource("wikimedia");
        item.setTitle("skincare b-roll.mp4");
        item.setType("video");
        item.setDownloadUrl("https://example.com/skincare.mp4");
        item.setLicense("CC BY-SA 4.0");
        item.setPageUrl("https://commons.wikimedia.org/wiki/File:skincare");

        CrawlJob mockJob = new CrawlJob();
        mockJob.setId(300L);

        when(crawler.searchPublicVideoQuick(eq("wikimedia"), eq("skincare"), anyInt(), isNull()))
                .thenReturn(List.of(item));
        when(crawlJobService.submitVideoItems(anyList(), eq("body")))
                .thenReturn(mockJob);

        MixParams params = new MixParams();
        params.setNamePrefix("skincare");

        MaterialGapService.AutoFillRequest req = new MaterialGapService.AutoFillRequest();
        req.setParams(params);
        req.setSources(List.of("wikimedia"));

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        assertTrue(result.isAny());
        assertEquals(300L, result.getCrawlJobIds().get(0));
        verify(crawler).searchPublicVideoQuick(eq("wikimedia"), eq("skincare"), anyInt(), isNull());
        verify(crawler, never()).searchPublicVideoQuick(eq("wikimedia"), eq("video clip b-roll"), anyInt(), any());
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private Material createVisual(Long id, String name, double duration, MaterialRole role) {
        Material m = new Material();
        m.setId(id);
        m.setName(name);
        m.setFilePath("C:/fixtures/" + name);
        m.setDurationSec(duration);
        m.setRole(role);
        m.setFileType(Material.FileType.video);
        m.setStatus(Material.Status.ready);
        return m;
    }
}
