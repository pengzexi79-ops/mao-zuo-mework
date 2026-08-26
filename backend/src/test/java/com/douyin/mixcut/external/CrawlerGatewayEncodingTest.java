package com.douyin.mixcut.external;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for the RFC 3986 path-segment encoding used when building Wikimedia and
 * Internet Archive page/download URLs. Regression: {@code URLEncoder} form-encoding (space -> '+')
 * was previously reused for path segments, producing broken links like
 * {@code /wiki/File:Cats+and+dogs.jpg} (MediaWiki does not decode '+' in paths).
 */
class CrawlerGatewayEncodingTest {

    @Test
    void encPathEncodesSpaceAsPercent20NeverPlus() {
        assertEquals("File%3ACats%20and%20dogs.jpg", CrawlerGateway.encPath("File:Cats and dogs.jpg"));
    }

    @Test
    void encPathPreservesRfc3986UnreservedCharacters() {
        assertEquals("a-z_A-Z.0-9~", CrawlerGateway.encPath("a-z_A-Z.0-9~"));
    }

    @Test
    void encPathEncodesReservedAndSeparatorsInsideASegment() {
        assertEquals("a%2Fb%3Fc%23d%25e%26f", CrawlerGateway.encPath("a/b?c#d%e&f"));
    }

    @Test
    void encPathEncodesUtf8BytesForNonAsciiTitles() {
        String encoded = CrawlerGateway.encPath("Café 音乐 片段.mp4");
        assertFalse(encoded.contains("+"), "path segments must not contain form-encoding '+'");
        assertTrue(encoded.contains("%C3%A9"), "é must be percent-encoded as UTF-8 bytes");
        assertTrue(encoded.contains("%20"), "spaces must be %20");
        assertTrue(encoded.matches("^[A-Za-z0-9._~%\\-]+$"), "only percent-escapes and unreserved chars allowed");
    }

    @Test
    void encPathHandlesNullOrEmpty() {
        assertEquals("", CrawlerGateway.encPath(null));
        assertEquals("", CrawlerGateway.encPath(""));
    }

    @Test
    void encPathIsStableForArchiveFileNamesWithSpaces() {
        // archive.org file names are path segments, not query parameters; '(' ')' are not
        // RFC 3986 unreserved characters and must be percent-encoded
        assertEquals("The%20Blue%20Danube%20%28Part%201%29.mp3", CrawlerGateway.encPath("The Blue Danube (Part 1).mp3"));
    }
}
