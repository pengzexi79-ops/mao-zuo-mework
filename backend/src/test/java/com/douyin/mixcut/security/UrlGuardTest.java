package com.douyin.mixcut.security;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests only the no-DNS syntax phase; production validate() still resolves every hostname. */
class UrlGuardTest {

    @Test
    void acceptsPublicHttpAndHttpsUrlSyntaxWithoutDns() {
        URI https = assertDoesNotThrow(() -> UrlGuard.validateSyntax("https://public.example/video.mp4?quality=720"));
        URI http = assertDoesNotThrow(() -> UrlGuard.validateSyntax("http://cdn.example:8080/path/file.m4a"));

        assertEquals("public.example", https.getHost());
        assertEquals("cdn.example", http.getHost());
    }

    @Test
    void rejectsLocalhostAndPrivateOrReservedNumericTargetsWithoutDns() {
        for (String url : new String[]{
                "http://localhost:8080/admin",
                "https://api.localhost/",
                "http://127.0.0.1/",
                "http://10.0.0.8/",
                "http://172.16.0.1/",
                "http://192.168.1.20/",
                "http://169.254.169.254/latest/meta-data",
                "http://[::1]/",
                "http://[fc00::1]/"
        }) {
            assertThrows(IllegalArgumentException.class, () -> UrlGuard.validateSyntax(url), url);
        }
    }

    @Test
    void rejectsUnsafeSchemesMalformedUrlsAndCredentials() {
        for (String url : new String[]{
                "file:///etc/passwd",
                "ftp://public.example/archive.mp4",
                "javascript:alert(1)",
                "https://user:secret@public.example/file.mp4",
                "https:///missing-host",
                "not a url"
        }) {
            assertThrows(IllegalArgumentException.class, () -> UrlGuard.validateSyntax(url), url);
        }
    }
}
