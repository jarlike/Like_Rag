package cn.like.rag.controller;

import cn.like.rag.model.RagOperationLog;
import cn.like.rag.service.OperationLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final OperationLogService operationLogService;

    public LogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public List<RagOperationLog> latest(@RequestParam(defaultValue = "80") int limit) {
        return operationLogService.latest(limit);
    }
}
