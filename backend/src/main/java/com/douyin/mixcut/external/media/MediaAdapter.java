package com.douyin.mixcut.external.media;

/** Marker contract for executable, explicitly registered media wire adapters. */
public interface MediaAdapter {
    enum Action { IMAGE, VIDEO_SUBMIT, VIDEO_POLL, VIDEO_DOWNLOAD, VOICE_SUBMIT }

    boolean supportsProtocol(String protocol);

    default boolean supportsAction(Action action) {
        return action != null;
    }
}
