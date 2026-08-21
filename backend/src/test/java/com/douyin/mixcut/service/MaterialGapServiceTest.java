package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.CrawlJob;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for MaterialGapService auto-fill: per-source circuit breaker (a dead public
 * source must not be re-hit on every request) and actionable, redacted failure results.
 */
@ExtendWith(MockitoExtension.class)
class MaterialGapServiceTest {

    @Mock private MaterialStore materialRepo;
    @Mock private ProjectRepo projectRepo;
    @Mock private MaterialFolderRepo folderRepo;
    @Mock private CrawlerGateway crawler;
    @Mock private CrawlJobService crawlJobService;

    private AppProps props;
    private MaterialGapService gapService;

    @BeforeEach
    void setUp() {
        props = new AppProps();
        gapService = new MaterialGapService(materialRepo, projectRepo, folderRepo, new MixPlanner(), crawler, crawlJobService, props);
    }

    @AfterEach
    void restoreBreakerCooldown() {
        MaterialGapService.breakerCooldownMs = 60_000L;
    }

    private MaterialGapService.AutoFillRequest request(String source) {
        MaterialGapService.AutoFillRequest req = new MaterialGapService.AutoFillRequest();
        req.setSources(List.of(source));
        req.setKeyword("food");
        return req;
    }

    @Test
    void breakerSkipsSourceAfterRepeatedFailuresWithoutHittingItAgain() {
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("Connection refused: commons.wikimedia.org"));

        MaterialGapService.AutoFillResult first = gapService.autoFill(request("wikimedia"));
        MaterialGapService.AutoFillResult second = gapService.autoFill(request("wikimedia"));
        MaterialGapService.AutoFillResult third = gapService.autoFill(request("wikimedia"));

        assertEquals("failed", first.getSourceResults().get(0).get("status"));
        assertEquals("failed", second.getSourceResults().get(0).get("status"));
        assertEquals("skipped_breaker", third.getSourceResults().get(0).get("status"));
        assertTrue((Long) third.getSourceResults().get(0).get("retryAfterSeconds") > 0,
                "breaker skip must carry a retry-after window");
        verify(crawler, times(2)).searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any());
    }

    @Test
    void breakerAllowsRetryAfterCooldownExpires() {
        MaterialGapService.breakerCooldownMs = 0L; // cooldown already expired on the next call
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        gapService.autoFill(request("wikimedia"));
        gapService.autoFill(request("wikimedia")); // two failures -> breaker opens (instantly expired)

        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setTitle("food b-roll.mp4");
        item.setDownloadUrl("https://example.com/food.mp4");
        item.setLicense("CC BY");
        CrawlJob job = new CrawlJob();
        job.setId(42L);
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any())).thenReturn(List.of(item));
        when(crawlJobService.submitVideoItems(anyList(), eq("body"))).thenReturn(job);

        MaterialGapService.AutoFillResult retry = gapService.autoFill(request("wikimedia"));

        assertEquals("queued", retry.getSourceResults().get(0).get("status"));
        verify(crawler, times(3)).searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any());
    }

    @Test
    void emptyResultsDoNotTripBreaker() {
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any())).thenReturn(List.of());
        when(crawler.searchPublicVideoQuick(eq("archive"), anyString(), anyInt(), any())).thenReturn(List.of());

        MaterialGapService.AutoFillRequest req = request("wikimedia");
        req.setSources(List.of("wikimedia", "archive"));

        for (int i = 0; i < 3; i++) {
            MaterialGapService.AutoFillResult r = gapService.autoFill(req);
            assertTrue(r.getSourceResults().stream().allMatch(m -> "no_results".equals(m.get("status"))));
        }
        verify(crawler, times(3)).searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any());
        verify(crawler, times(3)).searchPublicVideoQuick(eq("archive"), anyString(), anyInt(), any());
    }

    @Test
    void defaultSourcesAreOnlyNoLoginAutoProvidersAndNeverIncludeMixkit() {
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any())).thenReturn(List.of());
        when(crawler.searchPublicVideoQuick(eq("archive"), anyString(), anyInt(), any())).thenReturn(List.of());

        MaterialGapService.AutoFillRequest req = new MaterialGapService.AutoFillRequest();
        req.setKeyword("food");

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        List<String> keys = result.getSourceResults().stream().map(m -> String.valueOf(m.get("source"))).toList();
        assertEquals(List.of("wikimedia", "archive"), keys);
        assertTrue(result.getSourceResults().stream().allMatch(m -> "no_results".equals(m.get("status"))));
        verify(crawler, never()).searchAudio(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void defaultSourcesIncludePexelsOnlyWhenOfficialKeyIsConfigured() {
        props.setPexelsApiKey("test-key");
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any())).thenReturn(List.of());
        when(crawler.searchPublicVideoQuick(eq("archive"), anyString(), anyInt(), any())).thenReturn(List.of());
        when(crawler.searchPublicVideoQuick(eq("pexels"), anyString(), anyInt(), any())).thenReturn(List.of());

        MaterialGapService.AutoFillRequest req = new MaterialGapService.AutoFillRequest();
        req.setKeyword("food");
        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        List<String> keys = result.getSourceResults().stream().map(m -> String.valueOf(m.get("source"))).toList();
        assertEquals(List.of("wikimedia", "archive", "pexels"), keys);
        verify(crawler).searchPublicVideoQuick(eq("pexels"), anyString(), anyInt(), any());
    }

    @Test
    void productAndEndcardGapsRemainLocalAndDoNotCallPublicCrawler() {
        MaterialGapService.AutoFillRequest req = request("wikimedia");
        req.setRoles(List.of("product", "endcard"));

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        assertFalse(result.isAny());
        assertEquals(1, result.getSourceResults().size());
        Map<String, Object> entry = result.getSourceResults().get(0);
        assertEquals("local_required", entry.get("status"));
        assertTrue(String.valueOf(entry.get("message")).contains("本地素材库"));
        verifyNoInteractions(crawler);
        verifyNoInteractions(crawlJobService);
    }

    @Test
    void mixkitIsManualOnlyAndNeverQueuedOrAutoQueried() {
        MaterialGapService.AutoFillResult result = gapService.autoFill(request("mixkit"));

        Map<String, Object> entry = result.getSourceResults().get(0);
        assertEquals("manual_only", entry.get("status"));
        assertTrue(String.valueOf(entry.get("message")).contains("手动导入"));
        assertFalse(result.isAny());
        assertEquals(0, result.getTotalItemsQueued());
        verifyNoInteractions(crawler);
        verifyNoInteractions(crawlJobService);
    }

    @Test
    void noticeOnlyResultsReportFailureWithSourceHintInsteadOfFalseSuccess() {
        CrawlerGateway.RemoteItem notice = new CrawlerGateway.RemoteItem();
        notice.setSource("wikimedia");
        notice.setNotice(true);
        notice.setTitle("[检索失败] TimeoutException: commons.wikimedia.org 无响应");
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any())).thenReturn(List.of(notice));

        MaterialGapService.AutoFillResult result = gapService.autoFill(request("wikimedia"));

        Map<String, Object> entry = result.getSourceResults().get(0);
        assertEquals("failed", entry.get("status"));
        assertTrue(String.valueOf(entry.get("message")).contains("commons.wikimedia.org"),
                "notice text and remediation hint must surface, got: " + entry.get("message"));
        assertFalse(result.isAny());
        assertEquals(0, result.getTotalItemsQueued());
        verifyNoInteractions(crawlJobService);
    }

    @Test
    void noticeItemsAreNeverQueuedAlongsideRealItems() {
        CrawlerGateway.RemoteItem notice = new CrawlerGateway.RemoteItem();
        notice.setSource("wikimedia");
        notice.setNotice(true);
        notice.setTitle("[提示] 该条目需要人工确认");
        CrawlerGateway.RemoteItem real = new CrawlerGateway.RemoteItem();
        real.setSource("wikimedia");
        real.setTitle("food b-roll.mp4");
        real.setDownloadUrl("https://example.com/food.mp4");
        real.setLicense("CC BY 4.0");
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any()))
                .thenReturn(List.of(notice, real));
        CrawlJob job = new CrawlJob();
        job.setId(9L);
        when(crawlJobService.submitVideoItems(anyList(), eq("body"))).thenReturn(job);

        MaterialGapService.AutoFillResult result = gapService.autoFill(request("wikimedia"));

        assertEquals("queued", result.getSourceResults().get(0).get("status"));
        assertEquals(1, result.getTotalItemsQueued(), "notice items must be filtered before queueing");
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(crawlJobService).submitVideoItems(captor.capture(), eq("body"));
        assertEquals(1, captor.getValue().size());
        assertEquals(real, captor.getValue().get(0));
    }

    @Test
    void requestedEditorialRolesAreQueuedAsRoleSpecificJobs() {
        CrawlerGateway.RemoteItem first = remoteItem("hook scene.mp4");
        CrawlerGateway.RemoteItem second = remoteItem("body scene.mp4");
        CrawlerGateway.RemoteItem third = remoteItem("celebrity scene.mp4");
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any()))
                .thenReturn(List.of(first, second, third));
        AtomicInteger nextId = new AtomicInteger(10);
        when(crawlJobService.submitVideoItems(anyList(), anyString())).thenAnswer(invocation -> {
            CrawlJob job = new CrawlJob();
            job.setId((long) nextId.getAndIncrement());
            return job;
        });
        MaterialGapService.AutoFillRequest req = request("wikimedia");
        req.setRoles(List.of("hook", "body", "celebrity"));

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        var roles = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(crawlJobService, times(3)).submitVideoItems(anyList(), roles.capture());
        assertEquals(List.of("hook", "body", "celebrity"), roles.getAllValues());
        assertEquals(3, result.getTotalItemsQueued());
    }

    @Test
    void audioAndUnknownRolesRequireExplicitEditorialRole() {
        MaterialGapService.AutoFillRequest req = request("wikimedia");
        req.setRoles(List.of("voice", "bgm", "not-a-role"));

        MaterialGapService.AutoFillResult result = gapService.autoFill(req);

        assertFalse(result.isAny());
        assertTrue(result.getSourceResults().isEmpty());
        verifyNoInteractions(crawler);
        verifyNoInteractions(crawlJobService);
    }

    @Test
    void successResetsFailureCount() {
        AtomicInteger calls = new AtomicInteger();
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 1 || n == 3 || n == 4) {
                throw new RuntimeException("boom");
            }
            CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
            item.setTitle("ok.mp4");
            item.setDownloadUrl("https://example.com/ok.mp4");
            item.setLicense("CC BY");
            return List.of(item);
        });
        CrawlJob job = new CrawlJob();
        job.setId(7L);
        when(crawlJobService.submitVideoItems(anyList(), eq("body"))).thenReturn(job);

        // fail, success (reset), fail, fail (open) -> next call skipped
        assertEquals("failed", gapService.autoFill(request("wikimedia")).getSourceResults().get(0).get("status"));
        assertEquals("queued", gapService.autoFill(request("wikimedia")).getSourceResults().get(0).get("status"));
        assertEquals("failed", gapService.autoFill(request("wikimedia")).getSourceResults().get(0).get("status"));
        assertEquals("failed", gapService.autoFill(request("wikimedia")).getSourceResults().get(0).get("status"));
        assertEquals("skipped_breaker", gapService.autoFill(request("wikimedia")).getSourceResults().get(0).get("status"));
    }

    @Test
    void failureResultIsRedactedAndActionable() {
        when(crawler.searchPublicVideoQuick(eq("wikimedia"), anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("connect failed token=SUPERSECRET123"));

        MaterialGapService.AutoFillResult result = gapService.autoFill(request("wikimedia"));

        Map<String, Object> entry = result.getSourceResults().get(0);
        String message = String.valueOf(entry.get("message"));
        assertTrue(message.contains("检索失败"));
        assertTrue(message.contains("token=***"), "credentials must be redacted, got: " + message);
        assertFalse(message.contains("SUPERSECRET123"));
        assertTrue(message.contains("commons.wikimedia.org"),
                "failure must carry a remediation hint pointing at the source");
        assertTrue(entry.containsKey("elapsedMs"));
    }

    private CrawlerGateway.RemoteItem remoteItem(String title) {
        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setTitle(title);
        item.setDownloadUrl("https://example.com/" + title.replace(' ', '-'));
        item.setLicense("CC BY");
        item.setType("video");
        return item;
    }

    @Test
    void unsupportedSourceIsNotABreakerFailure() {
        MaterialGapService.AutoFillResult result = gapService.autoFill(request("freesound"));

        assertEquals("unsupported", result.getSourceResults().get(0).get("status"));
        // unsupported is not an attempt: no breaker state, and no crawler call
        verifyNoInteractions(crawler);
    }
}
