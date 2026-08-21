package com.douyin.mixcut.web;

import lombok.Data;

/** 统一响应体。前端只判断 ok 字段。 */
@Data
public class R<T> {
    private boolean ok;
    private T data;
    private String message;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.ok = true;
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(String msg) {
        R<T> r = new R<>();
        r.ok = false;
        r.message = msg;
        return r;
    }
}
