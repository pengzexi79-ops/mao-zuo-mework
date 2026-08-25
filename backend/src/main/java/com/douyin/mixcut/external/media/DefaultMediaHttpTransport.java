package com.douyin.mixcut.external.media;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded synchronous transport for an adapter request already validated by its protocol layer. */
@Component
public class DefaultMediaHttpTransport implements MediaHttpTransport {
    @Override
    public Response execute(Request request) throws Exception {
        URI uri = URI.create(request.url());
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setRequestMethod(request.method());
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(180_000);
        if (request.headers() != null) request.headers().forEach(connection::setRequestProperty);
        if (request.contentType() != null && !request.contentType().isBlank()) connection.setRequestProperty("Content-Type", request.contentType());
        byte[] body = request.body();
        if (body != null && body.length > 0) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) { output.write(body); }
        }
        try {
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            byte[] bytes = input == null ? new byte[0] : readLimited(input, 20L * 1024 * 1024);
            Map<String, String> headers = new LinkedHashMap<>();
            connection.getHeaderFields().forEach((key, values) -> {
                if (key != null && values != null && !values.isEmpty()) headers.put(key, values.get(0));
            });
            return new Response(status, headers, bytes);
        } finally {
            connection.disconnect();
        }
    }

    private byte[] readLimited(InputStream input, long limit) throws Exception {
        try (input; var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) throw new IllegalStateException("媒体 Provider 响应超过 20MB 限制");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
