package com.douyin.mixcut.security;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** Validates outbound URLs against unsafe schemes and server-side request forgery targets. */
public final class UrlGuard {

    private UrlGuard() {
    }

    /**
     * Validates a URL and resolves every DNS result before returning the normalized URL.
     *
     * <p>The syntax-only phase is deliberately separate for offline unit tests. Production callers
     * must use this method: it always follows syntax validation with DNS resolution and checks every
     * resolved address, so the test helper cannot bypass the production SSRF protection.</p>
     *
     * @throws IllegalArgumentException when the URL is malformed or targets a blocked address
     */
    public static String validate(String value) {
        URI uri = validateSyntax(value);
        resolveAndCheck(uri.getHost());
        return uri.toString();
    }

    /**
     * Resolves the host exactly once and validates every DNS answer against the SSRF blocklist.
     * Returns a single pinned address so callers can open the connection to that concrete address
     * without a second (connect-time) DNS lookup. This closes the DNS-rebinding / TOCTOU window
     * where {@link #validate(String)} resolves a public address but the subsequent
     * {@link java.net.HttpURLConnection} connect re-resolves to a private one.
     *
     * @throws IllegalArgumentException when the host cannot be resolved or any answer is blocked
     */
    public static InetAddress validateAndResolve(String host) {
        return resolveAndCheck(host)[0];
    }

    /** Returns every validated address (A/AAAA) so callers can fall back across records. */
    public static InetAddress[] validateAndResolveAll(String host) {
        return resolveAndCheck(host);
    }

    private static InetAddress[] resolveAndCheck(String host) {
        String normalized = normalizedHost(host);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalized);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("URL host cannot be resolved", e);
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("URL host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new IllegalArgumentException("Private or reserved addresses are not allowed");
            }
        }
        return addresses;
    }

    /**
     * Performs URI, scheme, local-hostname and numeric-literal checks without DNS lookup.
     * Package-private only for offline tests; do not use this method for outbound requests.
     */
    static URI validateSyntax(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }

        final URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid host without credentials");
        }

        String host = normalizedHost(uri.getHost());
        if (isLocalHostname(host)) {
            throw new IllegalArgumentException("Local hostnames are not allowed");
        }
        if (isBlockedNumericLiteral(host)) {
            throw new IllegalArgumentException("Private or reserved addresses are not allowed");
        }
        return uri;
    }

    private static String normalizedHost(String host) {
        String normalized = host;
        // URI implementations may expose IPv6 hosts with brackets; brackets are not IDN labels.
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (normalized.indexOf(':') >= 0) {
            return normalized;
        }
        try {
            return IDN.toASCII(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL host is invalid", e);
        }
    }

    /**
     * Offline numeric-literal classification. It intentionally does not call InetAddress so tests
     * cannot cause a resolver lookup. Hostnames are left to validate(), which resolves all answers.
     */
    private static boolean isBlockedNumericLiteral(String host) {
        if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("URL host is invalid");
            }
            int[] octets = new int[4];
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) {
                    throw new IllegalArgumentException("URL host is invalid");
                }
                try {
                    octets[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("URL host is invalid", e);
                }
                if (octets[i] > 255) {
                    throw new IllegalArgumentException("URL host is invalid");
                }
            }
            int a = octets[0], b = octets[1], c = octets[2], d = octets[3];
            return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127) || (a == 192 && b == 0 && c == 0)
                    || (a == 192 && b == 0 && c == 2) || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)
                    || a >= 240 || (a == 255 && b == 255 && c == 255 && d == 255);
        }
        if (host.indexOf(':') >= 0) {
            // URI has already validated IPv6 syntax. These patterns cover local/unique, mapped
            // IPv4 forms and the documentation range; full classification still runs after DNS.
            String value = host.toLowerCase(Locale.ROOT);
            return "::".equals(value) || "::1".equals(value) || value.startsWith("fe80:")
                    || value.startsWith("fc") || value.startsWith("fd") || value.startsWith("2001:db8:")
                    || value.startsWith("::ffff:");
        }
        return false;
    }

    private static boolean isLocalHostname(String host) {
        return "localhost".equals(host)
                || host.endsWith(".localhost")
                || "localhost.localdomain".equals(host)
                || "ip6-localhost".equals(host);
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            int d = bytes[3] & 0xff;
            return a == 0
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 192 && b == 0 && c == 0)
                    || (a == 192 && b == 0 && c == 2)
                    || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113)
                    || a >= 240
                    || (a == 255 && b == 255 && c == 255 && d == 255);
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // Unique-local (fc00::/7), documentation (2001:db8::/32), and IPv4-mapped.
            return (first & 0xfe) == 0xfc
                    || (first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d
                    && (bytes[3] & 0xff) == 0xb8)
                    || isIpv4Mapped(bytes);
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}
