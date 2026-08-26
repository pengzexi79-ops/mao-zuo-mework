package com.douyin.mixcut.web;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 兜底异常处理：任何异常都返回可读中文，不把堆栈甩给前端。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(R.fail(e.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(R.fail("当前操作不支持该请求方式，请刷新到最新应用后重试"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail("请求的功能不存在，请确认应用已更新并重启"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(R.fail("文件超过服务器上传限制（单文件最大 2GB）"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> unreadableRequest(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(R.fail("请求内容无法解析：请检查表单内容后重试（目录/密钥等字段需完整填写）"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<R<Void>> malformedUpload(MultipartException e) {
        log.warn("无法读取上传表单: {}", e.getMessage());
        return ResponseEntity.badRequest().body(R.fail("上传内容无法读取：请确认文件未被占用，必要时重新选择后再上传"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> other(Exception e, HttpServletResponse response) {
        // 响应已提交（例如视频/图片流已开始向客户端写入后客户端中断）时，
        // 不能再回写 JSON body —— 否则 Spring 会因当前 Content-Type 不是 JSON 而二次抛错
        // （HttpMessageNotWritableException: No converter for R with video/mp4）。
        // 此时直接结束当前响应，不再写 body，避免二次异常。
        if (response.isCommitted()) {
            log.info("请求响应已提交后发生异常（可能是客户端中断视频/图片流），已结束响应: {}", e.toString());
            try {
                response.getOutputStream().flush();
                response.getOutputStream().close();
            } catch (Exception ignored) {
                // 连接已断开，忽略关闭时的异常
            }
            return null;
        }
        // 若异常发生在视频/图片预览等已设置非 JSON Content-Type 的场景（尚未提交响应），
        // 重置 Content-Type 后回写统一 JSON 错误体。
        if (response.getContentType() != null
                && !MediaType.APPLICATION_JSON.includes(MediaType.parseMediaType(response.getContentType()))) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        log.error("未处理异常", e);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail("服务端错误: " + msg));
    }
}
