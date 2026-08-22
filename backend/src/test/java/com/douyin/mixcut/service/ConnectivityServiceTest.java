package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.external.SafeHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectivityServiceTest {
    @Test
    void unknownTargetIsRejectedWithoutNetworkCall() {
        SafeHttpClient http = mock(SafeHttpClient.class);
        ConnectivityService service = new ConnectivityService(http, new AppProps());
        assertThrows(IllegalArgumentException.class, () -> service.check("arbitrary-url"));
    }

    @Test
    void successfulTargetReportIsRedactedAndStructured() {
        SafeHttpClient http = mock(SafeHttpClient.class);
        when(http.get(any(), anyMap(), any(Boolean.TYPE))).thenReturn(new SafeHttpClient.Response(
                204, Map.of("X-Test", List.of("ok")), new byte[]{'s','e','c','r','e','t'}, "https://example.invalid/"));
        ConnectivityService service = new ConnectivityService(http, new AppProps());
        Map<String, Object> report = service.check("wikimedia");
        assertEquals("wikimedia", report.get("target"));
        assertEquals(true, report.get("reachable"));
        assertEquals(204, report.get("httpStatus"));
        assertFalse(report.containsKey("body"));
        assertTrue(report.containsKey("dns"));
        assertTrue(report.containsKey("tls"));
        assertTrue(report.containsKey("proxy"));
    }

    @Test
    void missingApiKeyDoesNotProbeRemoteService() {
        SafeHttpClient http = mock(SafeHttpClient.class);
        ConnectivityService service = new ConnectivityService(http, new AppProps());
        Map<String, Object> report = service.check("pexels");
        assertEquals(false, report.get("configured"));
        assertEquals("NOT_CONFIGURED", report.get("errorCode"));
        org.mockito.Mockito.verifyNoInteractions(http);
    }

    @Test
    void networkFailureIsClassifiedWithoutExceptionDetails() {
        SafeHttpClient http = mock(SafeHttpClient.class);
        when(http.get(any(), anyMap(), any(Boolean.TYPE))).thenThrow(new IllegalStateException("outbound HTTP request failed: API_KEY=secret"));
        ConnectivityService service = new ConnectivityService(http, new AppProps());
        Map<String, Object> report = service.check("archive");
        assertEquals(false, report.get("reachable"));
        assertEquals("NETWORK_FAILED", report.get("errorCode"));
        assertFalse(String.valueOf(report).contains("secret"));
    }
}
