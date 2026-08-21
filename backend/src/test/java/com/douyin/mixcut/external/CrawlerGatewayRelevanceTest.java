package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Project;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrawlerGatewayRelevanceTest {

    private final CrawlerGateway gateway = new CrawlerGateway(new AppProps(), new ProcRunner());

    @Test
    void publicVideoSearchUsesMappedEnglishIntentForChineseKeywords() {
        assertEquals("food", gateway.publicVideoSearchKeyword("食品 零食饮品"));
        assertEquals("digital", gateway.publicVideoSearchKeyword("3C 数码 开箱"));
        assertEquals("lifestyle b-roll", gateway.publicVideoSearchKeyword("未知中文主题"));
        assertEquals("snack", gateway.publicVideoSearchKeyword("零食口感测评"));
        assertEquals("home", gateway.publicVideoSearchKeyword("家居清洁"));
    }

    @Test
    void projectSearchKeepsMatchedItemsAndRecordsEvidence() {
        Project project = project("食品种草", "食品", "零食饮品", "口感 配料");
        CrawlerGateway.RemoteItem matched = item("Delicious snack cooking video", "food,kitchen");
        CrawlerGateway.RemoteItem unrelated = item("Cartoon animation episode", "cartoon");

        List<CrawlerGateway.RemoteItem> results = gateway.rankForProject(List.of(unrelated, matched), "食品", project, 10);

        assertEquals(1, results.size());
        assertSame(matched, results.get(0));
        assertEquals(project.getId(), matched.getProjectId());
        assertTrue(matched.getRelevanceScore() > 0);
        assertTrue(matched.getHitKeywords().contains("food"));
    }

    @Test
    void projectBannedTermsAreExcludedAndNoMatchReturnsEmpty() {
        Project project = project("香水种草", "香水", "香氛", "留香 优雅");
        project.setBannedWords("竞品,永久");
        CrawlerGateway.RemoteItem banned = item("竞品 perfume review", "fragrance");
        CrawlerGateway.RemoteItem unrelated = item("Football highlights", "sports");

        assertTrue(gateway.rankForProject(List.of(banned, unrelated), "香水", project, 10).isEmpty());
    }

    @Test
    void noProjectSearchDoesNotDiscardResultAndKeepsScoreVisible() {
        CrawlerGateway.RemoteItem item = item("Digital gadget unboxing", "tech,device");

        List<CrawlerGateway.RemoteItem> results = gateway.rankForProject(List.of(item), "数码", null, 10);

        assertEquals(1, results.size());
        assertTrue(item.getRelevanceScore() > 0);
        assertTrue(item.getHitKeywords().contains("digital"));
    }

    private Project project(String name, String category, String product, String sellingPoints) {
        Project project = new Project();
        project.setId(7L);
        project.setName(name);
        project.setCategory(category);
        project.setProduct(product);
        project.setSellingPoints(sellingPoints);
        return project;
    }

    private CrawlerGateway.RemoteItem item(String title, String tags) {
        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setTitle(title);
        item.setTags(tags);
        item.setDownloadUrl("https://cdn.example.com/item.mp4");
        item.setLicense("CC BY");
        return item;
    }
}
