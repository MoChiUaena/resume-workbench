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
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.CannotCreateTransactionException.class})
    ResponseEntity<?> database() { return error(503,"DATABASE_UNAVAILABLE","数据库暂时不可用，本次修改尚未保存。请保留页面，检查数据库后重试。"); }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> known(ApiException e) { return error(e.status, e.code, e.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size(jakarta.servlet.http.HttpServletRequest request) {
        if(request.getRequestURI().equals("/api/imports/docx/preview"))return error(413,"DOCX_TOO_LARGE","Word 文件须不超过 5 MiB，请拆分或压缩后重试。");
        if(request.getRequestURI().equals("/api/imports/pdf/preview"))return error(413,"PDF_TOO_LARGE","PDF 文件须不超过 5 MiB，请拆分或压缩后重试。");
        return error(413, "FILE_TOO_LARGE", "图片超过上传限制，请压缩后重试。");
    }
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
