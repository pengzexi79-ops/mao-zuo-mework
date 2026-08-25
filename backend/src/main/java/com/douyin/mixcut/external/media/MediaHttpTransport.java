package com.douyin.mixcut.external.media;

import java.nio.file.Path;
import java.util.Map;

/** Executes an already validated provider request; transport remains replaceable in adapter tests. */
public interface MediaHttpTransport {
    Response execute(Request request) throws Exception;

    /** Streams a binary response to a caller-owned staging file; implementations must not buffer it in memory. */
    default DownloadResponse download(Request request, Path staging, long maxBytes) throws Exception {
        throw new UnsupportedOperationException("此 MediaHttpTransport 不支持流式下载");
    }

    record Request(String method, String url, Map<String, String> headers, byte[] body, String contentType) {}
    record Response(int status, Map<String, String> headers, byte[] body) {}
    record DownloadResponse(int status, Map<String, String> headers, long bytesWritten) {}

    class DownloadLimitExceededException extends IllegalStateException {
        public DownloadLimitExceededException(long maxBytes) {
            super("媒体下载超过 " + maxBytes + " 字节限制");
        }
    }

    /** Response body buffered in memory exceeded the bounded transport limit (see DefaultMediaHttpTransport.readLimited). */
    class ResponseTooLargeException extends IllegalStateException {
        public ResponseTooLargeException(long maxBytes) {
            super("媒体 Provider 响应超过 " + maxBytes + " 字节限制");
        }
    }
}
