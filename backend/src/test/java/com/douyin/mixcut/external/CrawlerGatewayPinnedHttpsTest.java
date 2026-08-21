package com.douyin.mixcut.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for the pinned-IP HTTPS socket factory. Regression: JDK 17's HttpsClient skips
 * socket-layer endpoint identification when an application installs a custom HostnameVerifier, and
 * SNI is not honored for IP-literal URLs (RFC 6066), so pinned-IP HTTPS failed with
 * "HTTPS hostname wrong" (reproduced against Wikimedia IPv6). The factory must force
 * endpoint identification and the original SNI hostname onto every socket it hands back.
 */
@ExtendWith(MockitoExtension.class)
class CrawlerGatewayPinnedHttpsTest {

    @Test
    void factoryForwardsOriginalHostnameAndEnablesEndpointIdentification() throws Exception {
        SSLSocketFactory delegate = mock(SSLSocketFactory.class);
        SSLSocket ssl = mock(SSLSocket.class);
        SSLParameters params = new SSLParameters();
        when(ssl.getSSLParameters()).thenReturn(params);
        when(delegate.createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean())).thenReturn(ssl);

        InetAddress pinned = InetAddress.getByName("208.80.154.224"); // numeric literal: no DNS lookup
        CrawlerGateway.PinnedSniSslSocketFactory factory =
                new CrawlerGateway.PinnedSniSslSocketFactory("commons.wikimedia.org", pinned, delegate);

        // HttpsURLConnection passes the pinned IP literal as the URL host; the factory must ignore it.
        Socket out = factory.createSocket(new Socket(), "208.80.154.224", 443, true);

        assertSame(ssl, out);
        verify(delegate).createSocket(any(Socket.class), eq("commons.wikimedia.org"), eq(443), eq(true));
        verify(ssl).setSSLParameters(params);
        assertEquals("HTTPS", params.getEndpointIdentificationAlgorithm(),
                "endpoint identification must run inside the TLS handshake");
        List<SNIServerName> names = params.getServerNames();
        assertNotNull(names);
        assertEquals(1, names.size());
        assertEquals("commons.wikimedia.org", ((SNIHostName) names.get(0)).getAsciiName());
    }

    @Test
    void factorySkipsSniForIpLiteralHostButStillEnablesEndpointIdentification() throws Exception {
        SSLSocketFactory delegate = mock(SSLSocketFactory.class);
        SSLSocket ssl = mock(SSLSocket.class);
        SSLParameters params = new SSLParameters();
        when(ssl.getSSLParameters()).thenReturn(params);
        when(delegate.createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean())).thenReturn(ssl);

        InetAddress pinned = InetAddress.getByName("8.8.8.8");
        CrawlerGateway.PinnedSniSslSocketFactory factory =
                new CrawlerGateway.PinnedSniSslSocketFactory("8.8.8.8", pinned, delegate);

        // Must not throw: SNIHostName rejects IP literals, so no SNI entry may be built for them.
        Socket out = factory.createSocket(new Socket(), "8.8.8.8", 443, true);

        assertSame(ssl, out);
        verify(ssl).setSSLParameters(params);
        assertEquals("HTTPS", params.getEndpointIdentificationAlgorithm());
        assertNull(params.getServerNames());
    }

    @Test
    void stringVariantsConnectToPinnedAddressAndNeverReResolveHostname() throws Exception {
        SSLSocketFactory delegate = mock(SSLSocketFactory.class);
        SSLSocket ssl = mock(SSLSocket.class);
        SSLParameters params = new SSLParameters();
        when(ssl.getSSLParameters()).thenReturn(params);
        when(delegate.createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean())).thenReturn(ssl);

        // Local listener stands in for the pinned address: the factory must connect to this exact
        // address, not re-resolve "example.com" (which would hit DNS and bypass UrlGuard pinning).
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            InetAddress pinned = InetAddress.getByName("127.0.0.1");
            CrawlerGateway.PinnedSniSslSocketFactory factory =
                    new CrawlerGateway.PinnedSniSslSocketFactory("example.com", pinned, delegate);

            factory.createSocket("example.com", server.getLocalPort());
            try (Socket accepted = server.accept()) {
                assertNotNull(accepted.getInetAddress());
            }
            verify(delegate).createSocket(any(Socket.class), eq("example.com"), eq(server.getLocalPort()), eq(true));
            verify(delegate, never()).createSocket(eq("example.com"), anyInt());
        }
    }
}
