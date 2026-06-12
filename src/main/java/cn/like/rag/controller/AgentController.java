package cn.like.rag.controller;

import cn.like.rag.agent.AgentRequest;
import cn.like.rag.agent.AgentResponse;
import cn.like.rag.agent.AgentService;
import cn.like.rag.agent.AgentTrace;
import cn.like.rag.agent.AgentTraceService;
import cn.like.rag.sentinel.SentinelGuard;
import cn.like.rag.sentinel.SentinelResources;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * Agent（Plan-and-Solve + ReAct）问答入口。问答受 Sentinel {@code api.agent} QPS 保护，
 * 并可通过 traces 接口复盘执行轨迹。
 */
@Validated
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final AgentTraceService agentTraceService;
    private final SentinelGuard sentinelGuard;

    public AgentController(AgentService agentService,
                           AgentTraceService agentTraceService,
                           SentinelGuard sentinelGuard) {
        this.agentService = agentService;
        this.agentTraceService = agentTraceService;
        this.sentinelGuard = sentinelGuard;
    }

    @PostMapping("/chat")
    public AgentResponse chat(@Valid @RequestBody AgentRequest request) {
        return sentinelGuard.call(SentinelResources.API_AGENT,
                () -> agentService.answer(request.getQuestion(), request.getTopK()));
    }

    @GetMapping("/traces")
    public List<AgentTrace> traces(@RequestParam(defaultValue = "20") int limit) {
        return agentTraceService.latest(limit);
    }

    @GetMapping("/traces/{traceId}")
    public AgentTrace trace(@PathVariable String traceId) {
        return agentTraceService.get(traceId);
    }
}
