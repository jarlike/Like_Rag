package cn.like.rag.sentinel;

import cn.like.rag.config.RagProperties;
import cn.like.rag.service.OperationLogService;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 以编程方式加载 Sentinel 流控（FlowRule）与熔断降级（DegradeRule）规则，
 * 对应项目书第五章第 2、3 节。阈值来自 {@link RagProperties.Sentinel}，无需 Sentinel 控制台。
 *
 * <ul>
 *   <li>QPS 限流：上传、问答、Agent 入口。</li>
 *   <li>并发隔离（线程数）：重建索引、混合检索、embedding、LLM 生成、Agent 工具循环。</li>
 *   <li>熔断降级：LLM 慢调用（RT）+ LLM/embedding 异常比例。</li>
 * </ul>
 */
@Configuration
public class SentinelConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelConfig.class);

    private final RagProperties properties;
    private final OperationLogService operationLogService;

    public SentinelConfig(RagProperties properties, OperationLogService operationLogService) {
        this.properties = properties;
        this.operationLogService = operationLogService;
    }

    @PostConstruct
    public void init() {
        RagProperties.Sentinel cfg = properties.getSentinel();
        if (!cfg.isEnabled()) {
            log.info("Sentinel disabled (rag.sentinel.enabled=false), no rules loaded");
            return;
        }
        loadFlowRules(cfg);
        loadDegradeRules(cfg);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("uploadQps", cfg.getUploadQps());
        summary.put("chatQps", cfg.getChatQps());
        summary.put("reindexConcurrency", cfg.getReindexConcurrency());
        summary.put("embeddingMaxConcurrency", cfg.getEmbeddingMaxConcurrency());
        summary.put("hybridSearchMaxConcurrency", cfg.getHybridSearchMaxConcurrency());
        summary.put("llmMaxConcurrency", cfg.getLlmMaxConcurrency());
        summary.put("rerankMaxConcurrency", cfg.getRerankMaxConcurrency());
        summary.put("llmSlowCallRtMillis", cfg.getLlmSlowCallRtMillis());
        summary.put("exceptionRatio", cfg.getExceptionRatio());
        summary.put("circuitBreakWindowSeconds", cfg.getCircuitBreakWindowSeconds());
        operationLogService.info("SENTINEL_INIT", "Sentinel 规则已加载", null, summary);
        log.info("Sentinel rules loaded: {}", summary);
    }

    private void loadFlowRules(RagProperties.Sentinel cfg) {
        List<FlowRule> rules = new ArrayList<>();
        rules.add(qpsRule(SentinelResources.API_UPLOAD, cfg.getUploadQps()));
        rules.add(qpsRule(SentinelResources.API_CHAT, cfg.getChatQps()));
        rules.add(qpsRule(SentinelResources.API_AGENT, cfg.getChatQps()));
        rules.add(threadRule(SentinelResources.API_REINDEX, cfg.getReindexConcurrency()));
        rules.add(threadRule(SentinelResources.SERVICE_HYBRID_SEARCH, cfg.getHybridSearchMaxConcurrency()));
        rules.add(threadRule(SentinelResources.SERVICE_EMBEDDING, cfg.getEmbeddingMaxConcurrency()));
        rules.add(threadRule(SentinelResources.SERVICE_LLM_GENERATE, cfg.getLlmMaxConcurrency()));
        rules.add(threadRule(SentinelResources.SERVICE_RERANK, cfg.getRerankMaxConcurrency()));
        FlowRuleManager.loadRules(rules);
    }

    private void loadDegradeRules(RagProperties.Sentinel cfg) {
        List<DegradeRule> rules = new ArrayList<>();

        // LLM 慢调用熔断：RT 超过阈值且慢调用比例过半时熔断 circuitBreakWindow 秒。
        DegradeRule llmSlow = new DegradeRule(SentinelResources.SERVICE_LLM_GENERATE);
        llmSlow.setGrade(RuleConstant.DEGRADE_GRADE_RT);
        llmSlow.setCount(cfg.getLlmSlowCallRtMillis());
        llmSlow.setSlowRatioThreshold(0.5);
        llmSlow.setMinRequestAmount(cfg.getMinRequestAmount());
        llmSlow.setStatIntervalMs(cfg.getCircuitBreakWindowSeconds() * 1000);
        llmSlow.setTimeWindow(cfg.getCircuitBreakWindowSeconds());
        rules.add(llmSlow);

        // LLM 异常比例熔断。
        rules.add(exceptionRatioRule(SentinelResources.SERVICE_LLM_GENERATE, cfg));
        // embedding 异常比例熔断（外部 embedding 服务抖动时优雅降级）。
        rules.add(exceptionRatioRule(SentinelResources.SERVICE_EMBEDDING, cfg));

        DegradeRuleManager.loadRules(rules);
    }

    private DegradeRule exceptionRatioRule(String resource, RagProperties.Sentinel cfg) {
        DegradeRule rule = new DegradeRule(resource);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(cfg.getExceptionRatio());
        rule.setMinRequestAmount(cfg.getMinRequestAmount());
        rule.setStatIntervalMs(cfg.getCircuitBreakWindowSeconds() * 1000);
        rule.setTimeWindow(cfg.getCircuitBreakWindowSeconds());
        return rule;
    }

    private FlowRule qpsRule(String resource, int qps) {
        FlowRule rule = new FlowRule(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(Math.max(1, qps));
        return rule;
    }

    private FlowRule threadRule(String resource, int concurrency) {
        FlowRule rule = new FlowRule(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        rule.setCount(Math.max(1, concurrency));
        return rule;
    }
}
