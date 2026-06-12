package cn.like.rag.controller;

import cn.like.rag.model.ChatRequest;
import cn.like.rag.model.ChatResponse;
import cn.like.rag.model.SearchHit;
import cn.like.rag.sentinel.SentinelGuard;
import cn.like.rag.sentinel.SentinelResources;
import cn.like.rag.service.AnswerService;
import cn.like.rag.service.ConversationMemoryService;
import cn.like.rag.service.HybridSearchService;
import cn.like.rag.service.VectorSearchService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AnswerService answerService;
    private final VectorSearchService vectorSearchService;
    private final HybridSearchService hybridSearchService;
    private final ConversationMemoryService conversationMemoryService;
    private final SentinelGuard sentinelGuard;

    public ChatController(AnswerService answerService,
                          VectorSearchService vectorSearchService,
                          HybridSearchService hybridSearchService,
                          ConversationMemoryService conversationMemoryService,
                          SentinelGuard sentinelGuard) {
        this.answerService = answerService;
        this.vectorSearchService = vectorSearchService;
        this.hybridSearchService = hybridSearchService;
        this.conversationMemoryService = conversationMemoryService;
        this.sentinelGuard = sentinelGuard;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return sentinelGuard.call(SentinelResources.API_CHAT,
                () -> answerService.answer(request.getQuestion(), request.getTopK(), request.getSessionId()));
    }

    /** 重置某会话的多轮记忆（客户端"清空对话"）。幂等。 */
    @DeleteMapping("/chat/session/{sessionId}")
    public Map<String, Object> resetSession(@PathVariable String sessionId) {
        conversationMemoryService.clear(sessionId);
        return Map.of("sessionId", sessionId, "cleared", true);
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(required = false) Integer topK) {
        return vectorSearchService.search(q, topK);
    }

    @GetMapping("/search/sparse")
    public List<SearchHit> searchSparse(@RequestParam String q,
                                        @RequestParam(required = false) Integer topK) {
        return vectorSearchService.searchSparse(q, topK);
    }

    @GetMapping("/search/hybrid")
    public List<SearchHit> searchHybrid(@RequestParam String q,
                                        @RequestParam(required = false) Integer topK) {
        return hybridSearchService.search(q, topK);
    }
}
