package dev.localresume;

import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> known(ApiException e) { return error(e.status, e.code, e.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size() { return error(413, "FILE_TOO_LARGE", "图片超过上传限制，请压缩后重试。"); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception e) { return error(400, "INVALID_INPUT", "参数无效，请检查文字长度、图片尺寸和布局选项。"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        // No resume content, file paths, or exception messages in diagnostics.
        LoggerFactory.getLogger(ApiErrors.class).warn("Request failed: {}", e.getClass().getSimpleName());
        return error(500, "INTERNAL_ERROR", "本地服务暂时无法完成操作，请重试并检查服务状态。");
    }
    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
