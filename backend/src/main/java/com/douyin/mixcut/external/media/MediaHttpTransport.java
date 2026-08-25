package com.douyin.mixcut.external.media;

import java.util.Map;

/** Executes an already validated provider request; transport remains replaceable in adapter tests. */
public interface MediaHttpTransport {
    Response execute(Request request) throws Exception;

    record Request(String method, String url, Map<String, String> headers, byte[] body, String contentType) {}
    record Response(int status, Map<String, String> headers, byte[] body) {}
}
