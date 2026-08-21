package com.douyin.mixcut.security;

import com.douyin.mixcut.config.AppProps;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Optional shared-token protection for API and generated-file endpoints. */
public final class AccessTokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Mixcut-Token";
    private final AppProps props;

    public AccessTokenFilter(AppProps props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !isProtectedPath(path)
                || "/api/system/env".equals(path)
                || path.startsWith("/api/local-config/")
                || "/".equals(path)
                || "/index.html".equals(path)
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String expected = props.getAccessToken();
        // The desktop UI is served from this same machine. Keep local browser use seamless
        // while requiring the token for every non-loopback device on the LAN.
        if (!StringUtils.hasText(expected) || isLoopback(request.getRemoteAddr())) {
            filterChain.doFilter(request, response);
            return;
        }

        String supplied = request.getHeader(TOKEN_HEADER);
        // img/video/audio 标签无法设置自定义请求头；仅对浏览器媒体 GET 请求允许显式 token 参数。
        // 该值只由前端临时附加，不写入数据库或应用日志。
        if ((supplied == null || supplied.isBlank()) && "GET".equalsIgnoreCase(request.getMethod())
                && (request.getRequestURI().startsWith("/api/materials/")
                || request.getRequestURI().startsWith("/api/jobs/outputs/")
                || request.getRequestURI().startsWith("/files/"))) {
            supplied = request.getParameter("access_token");
        }
        if (supplied == null || !sameToken(expected, supplied)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"message\":\"Unauthorized\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isLoopback(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress) || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }

    private boolean isProtectedPath(String path) {
        return path.equals("/api") || path.startsWith("/api/")
                || path.equals("/files") || path.startsWith("/files/");
    }

    private String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return uri.substring(context == null ? 0 : context.length());
    }

    private boolean sameToken(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
