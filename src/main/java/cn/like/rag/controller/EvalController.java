package cn.like.rag.controller;

import cn.like.rag.eval.EvalReport;
import cn.like.rag.eval.EvalRequest;
import cn.like.rag.eval.RetrievalEvalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索评测入口：对 dense / sparse / hybrid 三路输出 nDCG@K / Recall@K / MRR 并做回归门禁判定。
 * 评测语料需先通过 /api/documents 入库，qrels 默认按 documentId 标注（relevanceField=document）。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final RetrievalEvalService retrievalEvalService;

    public EvalController(RetrievalEvalService retrievalEvalService) {
        this.retrievalEvalService = retrievalEvalService;
    }

    @PostMapping("/run")
    public EvalReport run(@RequestBody EvalRequest request) {
        return retrievalEvalService.run(request);
    }
}
