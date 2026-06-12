package cn.like.rag.controller;

import cn.like.rag.sentinel.RateLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /**
     * 限流/熔断专用响应：HTTP 429 + 明确的限流原因与重试建议，
     * 让调用方区分"被限流/服务繁忙"与"知识库没有答案"（项目书第五章降级策略 & 第十章风险 5）。
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> rateLimited(RateLimitException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "RATE_LIMITED");
        body.put("message", e.getMessage());
        body.put("resource", e.getResource());
        body.put("blockType", e.getBlockType());
        body.put("retryable", true);
        body.put("suggestion", "请降低请求频率或稍后重试；该错误表示服务被保护性限流/熔断，并非知识库无答案。");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> exception(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
    }
}
