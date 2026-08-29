package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pexels 官方视频 API 集成聚焦测试：
 * - 未配置 Key 时返回显式凭据提示条目，且不发起任何请求
 * - 检索 URL 只指向官方端点（api.pexels.com/v1/videos/search）且定向竖版
 * - 结果映射携带 pageUrl/downloadUrl/creator/许可元数据，rendition 优先竖版 HD mp4
 * - 失败/限流返回可操作的提示条目，且提示中绝不包含 API Key
 */
class CrawlerGatewayPexelsTest {

    private final CrawlerGateway gateway = new CrawlerGateway(new AppProps(), new ProcRunner());
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void missingKeyReturnsCredentialGatedNoticeAndNeverSearches() {
        // AppProps 默认 pexelsApiKey=""：显式选择 pexels 必须给出配置提示，而不是静默空结果
        List<CrawlerGateway.RemoteItem> out = gateway.searchVideo("pexels", "风景", 5);

        assertEquals(1, out.size(), "缺 Key 时显式检索 pexels 应返回 1 条提示");
        CrawlerGateway.RemoteItem it = out.get(0);
        assertTrue(it.isNotice());
        assertEquals("pexels", it.getSource());
        assertEquals("APP_PEXELS_API_KEY", it.getConfigKey(), "提示必须携带配置项标识");
        assertEquals("https://www.pexels.com/api/", it.getAuthUrl(), "提示必须携带官方申请入口");
        assertEquals("notice", it.getLicense());
        assertTrue(it.getTitle().contains("[未配置]"));
    }

    @Test
    void imageSearchReusesPexelsCredentialAndReturnsConfigurationNotice() {
        List<CrawlerGateway.RemoteItem> out = gateway.searchImage("pexels", "产品背景", 5);

        assertEquals(1, out.size());
        assertTrue(out.get(0).isNotice());
        assertEquals("image", gateway.supportsImageSource("pexels") ? "image" : "");
        assertEquals("APP_PEXELS_API_KEY", out.get(0).getConfigKey());
    }

    @Test
    void mapsPexelsImageWithPreviewAndLicenseMetadata() throws Exception {
        JsonNode photo = om.readTree("""
                {"id":123,"url":"https://www.pexels.com/photo/123/","photographer":"A Photographer",
                 "src":{"large2x":"https://images.pexels.com/photo/large.jpg","medium":"https://images.pexels.com/photo/medium.jpg"}}
                """);

        CrawlerGateway.RemoteItem item = CrawlerGateway.mapPexelsImage(photo);

        assertEquals("image", item.getType());
        assertEquals("https://images.pexels.com/photo/large.jpg", item.getDownloadUrl());
        assertEquals("https://images.pexels.com/photo/medium.jpg", item.getPreviewUrl());
        assertEquals("Pexels License (免费商用)", item.getLicense());
    }

    @Test
    void searchUrlClampsPerPageToApiBounds() {
        assertTrue(gateway.pexelsSearchUrl("风景", 1).contains("per_page=3"), "per_page 下限 3");
        assertTrue(gateway.pexelsSearchUrl("风景", 200).contains("per_page=80"), "per_page 上限 80");
        assertTrue(gateway.pexelsSearchUrl("风景", 40).contains("per_page=40"));
    }

    @Test
    void searchUrlPointsAtOfficialEndpointWithPortraitOrientation() {
        String url = gateway.pexelsSearchUrl("城市 夜景", 12);
        assertTrue(url.startsWith("https://api.pexels.com/v1/videos/search?"), "必须使用官方检索端点");
        assertTrue(url.contains("query="), "必须携带关键词查询参数");
        assertTrue(url.contains("orientation=portrait"), "检索必须定向竖版以匹配抖音画布");
        assertTrue(url.contains("per_page=12"), "per_page 必须透传限制");
        assertTrue(url.contains("query=%E5%9F%8E%E5%B8%82"), "关键词必须 UTF-8 编码");
        assertFalse(url.toLowerCase().contains("key="), "API Key 绝不能出现在 URL 里");
    }

    @Test
    void mapsVideoResultWithCreatorLicenseAndVerticalHdRendition() throws Exception {
        JsonNode video = om.readTree(sampleVideoJson());

        CrawlerGateway.RemoteItem it = CrawlerGateway.mapPexelsVideo(video);

        assertNotNull(it);
        assertEquals("pexels", it.getSource());
        assertEquals("video", it.getType());
        assertTrue(it.getTitle().contains("Engin Akyurt"), "标题应携带创作者名");
        assertEquals("https://www.pexels.com/video/3571264/", it.getPageUrl(), "pageUrl 指向视频详情页");
        assertTrue(it.getTags().contains("Engin Akyurt"), "创作者元数据应进入 tags");
        assertTrue(it.getTags().contains("https://www.pexels.com/@enginakyurt"), "创作者主页应进入 tags 供核验");
        assertEquals(5.0, it.getDuration());
        assertEquals("Pexels License (免费商用)", it.getLicense());
        assertEquals("https://www.pexels.com/license/", it.getLicenseUrl());
        assertEquals("https://player.vimeo.com/external/3571264.hd.mp4?s=portrait-hd", it.getDownloadUrl(),
                "应挑中竖版 HD mp4 直链");
    }

    @Test
    void renditionPrefersVerticalHdMp4ThenUhdThenSd() throws Exception {
        // 混排：横版 hd / 竖版 sd / 竖版 uhd / 竖版 hd → 必须选竖版 hd
        JsonNode files = om.readTree("""
                [ {"quality":"hd","file_type":"video/mp4","width":1920,"height":1080,"link":"https://cdn/landscape-hd.mp4"},
                  {"quality":"sd","file_type":"video/mp4","width":640,"height":1136,"link":"https://cdn/portrait-sd.mp4"},
                  {"quality":"uhd","file_type":"video/mp4","width":2160,"height":3840,"link":"https://cdn/portrait-uhd.mp4"},
                  {"quality":"hd","file_type":"video/mp4","width":1080,"height":1920,"link":"https://cdn/portrait-hd.mp4"} ]
                """);
        assertEquals("https://cdn/portrait-hd.mp4", CrawlerGateway.pickPexelsRendition(files));
    }

    @Test
    void renditionSkipsNonMp4AndFallsBackWhenNoVerticalFile() throws Exception {
        // 只有 hls 直链时不能返回空：mp4 优先是偏好不是硬约束
        JsonNode onlyHls = om.readTree("""
                [ {"quality":"hd","file_type":"video/ts","width":1080,"height":1920,"link":"https://cdn/portrait-hls.m3u8"},
                  {"quality":"hd","file_type":"video/mp4","width":1920,"height":1080,"link":"https://cdn/landscape-hd.mp4"} ]
                """);
        assertEquals("https://cdn/landscape-hd.mp4", CrawlerGateway.pickPexelsRendition(onlyHls),
                "无竖版时回退到质量最好的 mp4");

        JsonNode hlsOnly = om.readTree("""
                [ {"quality":"sd","file_type":"video/ts","width":640,"height":360,"link":"https://cdn/sd-hls.m3u8"} ]
                """);
        assertEquals("https://cdn/sd-hls.m3u8", CrawlerGateway.pickPexelsRendition(hlsOnly),
                "仅有非 mp4 时回退到唯一可用直链");
    }

    @Test
    void errorNoticesAreActionableAndNeverContainTheKey() {
        CrawlerGateway.RemoteItem limited = CrawlerGateway.pexelsErrorNotice(429);
        assertNotNull(limited);
        assertTrue(limited.getTitle().contains("[限流]"));
        assertEquals("APP_PEXELS_API_KEY", limited.getConfigKey());

        CrawlerGateway.RemoteItem auth = CrawlerGateway.pexelsErrorNotice(401);
        assertNotNull(auth);
        assertTrue(auth.getTitle().contains("[鉴权失败]"));

        CrawlerGateway.RemoteItem server = CrawlerGateway.pexelsErrorNotice(500);
        assertNotNull(server);
        assertTrue(server.getTitle().contains("[检索失败]"));
        assertTrue(server.getTitle().contains("500"));

        assertNull(CrawlerGateway.pexelsErrorNotice(-1), "连接层失败返回 null，由日志兜底，不向用户展示");
        assertNull(CrawlerGateway.pexelsErrorNotice(0));

        for (CrawlerGateway.RemoteItem it : List.of(limited, auth, server)) {
            assertFalse(it.getTitle().contains("sk-"), "提示文案绝不能泄露 Key 片段");
        }
    }

    @Test
    void redactionStripsAuthorizationHeaderValuesFromLogs() {
        // 回归保护：即使异常消息里混入 Authorization 头，redact 也必须抹掉凭据
        String safe = CrawlerGateway.safeError(new RuntimeException("GET failed: https://api.pexels.com/ Authorization: pexels-secret-key-123"));
        assertFalse(safe.contains("pexels-secret-key-123"));
        assertTrue(safe.contains("Authorization=***") || safe.contains("Bearer ***"), "redact 必须替换为掩码");
    }

    private String sampleVideoJson() {
        return """
                {
                  "id": 3571264,
                  "width": 1080,
                  "height": 1920,
                  "url": "https://www.pexels.com/video/3571264/",
                  "image": "https://images.pexels.com/videos/3571264/poster.jpg",
                  "duration": 5,
                  "user": { "id": 7880, "name": "Engin Akyurt", "url": "https://www.pexels.com/@enginakyurt" },
                  "video_files": [
                    { "id": 857240, "quality": "hd", "file_type": "video/mp4", "width": 1920, "height": 1080,
                      "link": "https://player.vimeo.com/external/3571264.hd.mp4?s=landscape-hd" },
                    { "id": 857241, "quality": "sd", "file_type": "video/mp4", "width": 640, "height": 1136,
                      "link": "https://player.vimeo.com/external/3571264.sd.mp4?s=portrait-sd" },
                    { "id": 857242, "quality": "hd", "file_type": "video/mp4", "width": 1080, "height": 1920,
                      "link": "https://player.vimeo.com/external/3571264.hd.mp4?s=portrait-hd" },
                    { "id": 857243, "quality": "uhd", "file_type": "video/mp4", "width": 2160, "height": 3840,
                      "link": "https://player.vimeo.com/external/3571264.uhd.mp4?s=portrait-uhd" }
                  ],
                  "video_pictures": [ { "id": 857244, "picture": "https://images.pexels.com/videos/3571264/frames/1.jpg" } ]
                }
                """;
    }
}
