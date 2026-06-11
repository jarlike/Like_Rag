package cn.like.rag.controller;

import cn.like.rag.model.ChatRequest;
import cn.like.rag.model.ChatResponse;
import cn.like.rag.model.SearchHit;
import cn.like.rag.service.AnswerService;
import cn.like.rag.service.VectorSearchService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AnswerService answerService;
    private final VectorSearchService vectorSearchService;

    public ChatController(AnswerService answerService, VectorSearchService vectorSearchService) {
        this.answerService = answerService;
        this.vectorSearchService = vectorSearchService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return answerService.answer(request.getQuestion(), request.getTopK());
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(required = false) Integer topK) {
        return vectorSearchService.search(q, topK);
    }
}
