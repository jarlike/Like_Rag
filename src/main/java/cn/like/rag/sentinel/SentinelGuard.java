package cn.like.rag.sentinel;

import cn.like.rag.service.OperationLogService;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Sentinel 调用包装器（基于 SphU.entry 显式埋点，避免对 AspectJ/代理的依赖，契合本项目"始终可运行"的设计）。
 *
 * <p>统一负责三件事：
 * <ol>
 *   <li>进入资源（QPS/并发/热点限流，熔断由 DegradeRule 控制）。</li>
 *   <li>命中限流/熔断时记录降级事件到操作日志，并执行调用方提供的降级逻辑（默认抛 {@link RateLimitException}）。</li>
 *   <li>把业务异常通过 {@link Tracer} 上报给 Sentinel，喂给异常比例熔断统计。</li>
 * </ol>
 *
 * 规则本身在 {@link SentinelConfig} 中以编程方式加载。
 */
@Component
public class SentinelGuard {

    private final OperationLogService operationLogService;

    public SentinelGuard(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /**
     * 受保护地执行有返回值的逻辑，命中限流/熔断时抛出 {@link RateLimitException}。
     */
    public <T> T call(String resource, Supplier<T> action) {
        return call(resource, action, (res, ex) -> {
            throw new RateLimitException(res, ex.getClass().getSimpleName(), ex);
        });
    }

    /**
     * 受保护地执行有返回值的逻辑，命中限流/熔断时返回 {@code fallback} 的结果（用于降级而非直接报错）。
     */
    public <T> T callOrElse(String resource, Supplier<T> action, Supplier<T> fallback) {
        return call(resource, action, (res, ex) -> fallback.get());
    }

    /**
     * 受保护地执行无返回值的逻辑，命中限流/熔断时抛出 {@link RateLimitException}。
     */
    public void run(String resource, Runnable action) {
        call(resource, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 核心实现：进入资源 → 执行 → 退出。命中 {@link BlockException} 走 {@code onBlock}，
     * 业务异常先 {@link Tracer#traceEntry} 上报再原样抛出。
     */
    public <T> T call(String resource, Supplier<T> action, BiFunction<String, BlockException, T> onBlock) {
        Entry entry = null;
        try {
            entry = SphU.entry(resource, EntryType.IN);
            return action.get();
        } catch (BlockException blockEx) {
            logBlock(resource, blockEx);
            return onBlock.apply(resource, blockEx);
        } catch (RuntimeException bizEx) {
            if (entry != null) {
                Tracer.traceEntry(bizEx, entry);
            }
            throw bizEx;
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private void logBlock(String resource, BlockException blockEx) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resource", resource);
        detail.put("blockType", blockEx.getClass().getSimpleName());
        if (blockEx.getRule() != null) {
            detail.put("rule", String.valueOf(blockEx.getRule()));
        }
        operationLogService.error("SENTINEL_BLOCK", "Sentinel 拦截请求并降级", null, detail);
    }
}
