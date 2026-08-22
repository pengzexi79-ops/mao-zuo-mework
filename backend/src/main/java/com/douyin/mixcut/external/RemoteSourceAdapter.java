package com.douyin.mixcut.external;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Provider-specific public media search boundary. Transport and job orchestration stay outside. */
public interface RemoteSourceAdapter {
    String sourceKey();

    boolean supports(String type);

    List<CrawlerGateway.RemoteItem> map(JsonNode response, String type, int limit);

    String query(String keyword, String type, int limit);
}
