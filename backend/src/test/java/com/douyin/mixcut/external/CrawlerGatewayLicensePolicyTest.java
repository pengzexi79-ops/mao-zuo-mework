package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for the real-asset source policy of the no-login automatic providers
 * (Wikimedia Commons / Internet Archive):
 * <ul>
 *   <li>license whitelist: only CC0 / Public Domain / CC BY may pass — CC BY-SA, CC BY-NC,
 *       CC BY-ND and unlicensed items must be rejected;</li>
 *   <li>actionable license metadata: licenseUrl is carried on RemoteItem and license
 *       URLs are mapped to readable labels;</li>
 *   <li>Wikimedia API query keeps the correct encoded {@code |} separator for iiprop
 *       (no raw pipe, no double-encoding);</li>
 *   <li>placeholder/demo media titles are excluded from unattended auto-fill;</li>
 *   <li>Pixabay remains a credential-gated provider: without APP_PIXABAY_API_KEY it returns
 *       a visible notice, never a silent empty list or a download.</li>
 * </ul>
 */
class CrawlerGatewayLicensePolicyTest {

    private final CrawlerGateway gateway = new CrawlerGateway(new AppProps(), new ProcRunner());

    @Test
    void remoteImportValidationRejectsUnsupportedSourceAndUnsafeUrl() {
        CrawlerGateway.RemoteItem unsupported = new CrawlerGateway.RemoteItem();
        unsupported.setType("video");
        unsupported.setSource("coverr");
        unsupported.setDownloadUrl("https://example.com/video.mp4");
        assertThrows(IllegalArgumentException.class, () -> gateway.validateRemoteItem(unsupported, "video"));

        CrawlerGateway.RemoteItem privateUrl = new CrawlerGateway.RemoteItem();
        privateUrl.setType("video");
        privateUrl.setSource("wikimedia");
        privateUrl.setDownloadUrl("http://127.0.0.1/video.mp4");
        assertThrows(IllegalArgumentException.class, () -> gateway.validateRemoteItem(privateUrl, "video"));
    }

    @Test
    void remoteImportValidationAcceptsImplementedPublicSourceShape() {
        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setType("video");
        item.setSource("wikimedia");
        item.setTitle("public clip");
        item.setLicense("CC0");
        item.setDownloadUrl("https://example.com/video.mp4");
        assertEquals("wikimedia", gateway.validateRemoteItem(item, "video").getSource());
    }

    // ---------------------------------------------------------------
    //  License whitelist (CC0 / Public Domain / CC BY only)
    // ---------------------------------------------------------------

    @Test
    void whitelistAcceptsCc0InEveryForm() {
        assertTrue(CrawlerGateway.isWhitelistedLicense("CC0"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("CC0 1.0"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("CC0 1.0 Universal"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("https://creativecommons.org/publicdomain/zero/1.0/"));
    }

    @Test
    void whitelistAcceptsPublicDomainInEveryForm() {
        assertTrue(CrawlerGateway.isWhitelistedLicense("Public domain"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("Public Domain Mark 1.0"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("https://creativecommons.org/publicdomain/mark/1.0/"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("CC-PD"));
    }

    @Test
    void whitelistAcceptsCcByButRejectsDerivativeAndNonCommercialVariants() {
        assertTrue(CrawlerGateway.isWhitelistedLicense("CC BY 4.0"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("CC BY 3.0 Unported"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("Attribution 4.0 International (CC BY 4.0)"));
        assertTrue(CrawlerGateway.isWhitelistedLicense("https://creativecommons.org/licenses/by/4.0/"));

        assertFalse(CrawlerGateway.isWhitelistedLicense("CC BY-SA 4.0"));
        assertFalse(CrawlerGateway.isWhitelistedLicense("CC BY-NC 4.0"));
        assertFalse(CrawlerGateway.isWhitelistedLicense("CC BY-NC-SA 4.0"));
        assertFalse(CrawlerGateway.isWhitelistedLicense("CC BY-ND 4.0"));
        assertFalse(CrawlerGateway.isWhitelistedLicense("https://creativecommons.org/licenses/by-sa/4.0/"));
        assertFalse(CrawlerGateway.isWhitelistedLicense("https://creativecommons.org/licenses/by-nc/4.0/"));
    }

    @Test
    void whitelistRejectsUnlicensedOrUnknownValues() {
        assertFalse(CrawlerGateway.isWhitelistedLicense(null));
        assertFalse(CrawlerGateway.isWhitelistedLicense(""));
        assertFalse(CrawlerGateway.isWhitelistedLicense("All rights reserved"));
        assertFalse(CrawlerGateway.isWhitelistedLicense("custom license"));
        assertFalse(CrawlerGateway.isWhitelistedLicense("Standard YouTube License"));
    }

    // ---------------------------------------------------------------
    //  Actionable license metadata
    // ---------------------------------------------------------------

    @Test
    void licenseLabelMapsUrlsToReadableShortNames() {
        assertEquals("CC0", CrawlerGateway.licenseLabel("https://creativecommons.org/publicdomain/zero/1.0/"));
        assertEquals("Public Domain", CrawlerGateway.licenseLabel("https://creativecommons.org/publicdomain/mark/1.0/"));
        assertEquals("CC BY 4.0", CrawlerGateway.licenseLabel("https://creativecommons.org/licenses/by/4.0/"));
        assertEquals("CC BY 3.0", CrawlerGateway.licenseLabel("https://creativecommons.org/licenses/by/3.0/"));
        assertEquals("", CrawlerGateway.licenseLabel(null));
    }

    @Test
    void remoteItemCarriesLicenseUrlForVerification() {
        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setLicense("CC BY 4.0");
        item.setLicenseUrl("https://creativecommons.org/licenses/by/4.0/");

        assertEquals("https://creativecommons.org/licenses/by/4.0/", item.getLicenseUrl());
        assertNull(item.getDownloadUrl(), "license metadata must not fabricate a download URL");
    }

    // ---------------------------------------------------------------
    //  Wikimedia query: iiprop separator encoding
    // ---------------------------------------------------------------

    @Test
    void wikimediaQueryUsesEncodedPipeSeparatorForIiprop() {
        String query = gateway.wikimediaQuery("food cooking", "video", 5);

        assertTrue(query.contains("iiprop=url%7Cextmetadata"),
                "iiprop multi-value separator must be the encoded pipe %7C, got: " + query);
        assertFalse(query.contains("iiprop=url|extmetadata"),
                "raw pipe must not appear in the query string: " + query);
        assertFalse(query.contains("%257C"), "iiprop separator must not be double-encoded: " + query);
        assertFalse(query.contains("iiprop=url,extmetadata"),
                "MediaWiki does not split iiprop on commas: " + query);
        assertTrue(query.contains("gsrsearch=food+cooking+filetype%3Avideo"), query);
        assertTrue(query.contains("gsrnamespace=6"), query);
    }

    // ---------------------------------------------------------------
    //  Placeholder / demo media exclusion (unattended auto sources)
    // ---------------------------------------------------------------

    @Test
    void demoPlaceholderTitlesAreExcluded() {
        assertTrue(CrawlerGateway.isDemoPlaceholderTitle("Demo.ogg"));
        assertTrue(CrawlerGateway.isDemoPlaceholderTitle("File:Example.jpg"));
        assertTrue(CrawlerGateway.isDemoPlaceholderTitle("Sample.mp3"));
        assertTrue(CrawlerGateway.isDemoPlaceholderTitle("Test.ogg"));
        assertTrue(CrawlerGateway.isDemoPlaceholderTitle("Placeholder image.png"));
        assertTrue(CrawlerGateway.isDemoPlaceholderTitle("A demo reel.mp4"));
    }

    @Test
    void realMediaTitlesAreNotExcluded() {
        assertFalse(CrawlerGateway.isDemoPlaceholderTitle("Food cooking video.mp4"));
        assertFalse(CrawlerGateway.isDemoPlaceholderTitle("Mountain landscape 4K.webm"));
        assertFalse(CrawlerGateway.isDemoPlaceholderTitle("The Blue Danube (Part 1).mp3"));
        assertFalse(CrawlerGateway.isDemoPlaceholderTitle(null));
        assertFalse(CrawlerGateway.isDemoPlaceholderTitle(""));
    }

    // ---------------------------------------------------------------
    //  Credential-gated providers (Pixabay official API): no key -> visible notice
    // ---------------------------------------------------------------

    @Test
    void pixabayWithoutApiKeyIsACredentialGatedNoticeNotSilentEmpty() {
        List<CrawlerGateway.RemoteItem> items = gateway.searchVideo("pixabay", "food", 5);

        assertEquals(1, items.size());
        CrawlerGateway.RemoteItem item = items.get(0);
        assertTrue(item.isNotice(), "missing key must surface as a notice, not an empty result");
        assertTrue(item.getTitle().contains("API Key"), item.getTitle());
        assertEquals("pixabay", item.getSource());
        assertNull(item.getDownloadUrl(), "a notice must never carry a download URL");
        assertEquals("APP_PIXABAY_API_KEY", item.getConfigKey());
    }
}
