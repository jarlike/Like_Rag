package cn.like.rag.sentinel;

/**
 * 业务侧统一的限流/熔断异常。被 {@code SentinelGuard} 在捕获 Sentinel {@code BlockException}
 * 后抛出，由 {@code ApiExceptionHandler} 翻译为 HTTP 429，并携带明确的限流原因，
 * 避免调用方把"被限流"误解为"知识库没有答案"（对应项目书第五章降级策略）。
 */
public class RateLimitException extends RuntimeException {

    private final String resource;
    private final String blockType;

    public RateLimitException(String resource, String blockType, Throwable cause) {
        super("资源 [" + resource + "] 触发 Sentinel 保护(" + blockType + ")，服务繁忙，请稍后重试", cause);
        this.resource = resource;
        this.blockType = blockType;
    }

    public String getResource() {
        return resource;
    }

    public String getBlockType() {
        return blockType;
    }
}
