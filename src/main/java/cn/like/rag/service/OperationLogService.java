package cn.like.rag.service;

import cn.like.rag.model.RagOperationLog;
import cn.like.rag.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OperationLogService {

    private static final Logger logger = LoggerFactory.getLogger(OperationLogService.class);

    private final OperationLogRepository operationLogRepository;

    public OperationLogService(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    public void info(String type, String message, String documentId, Map<String, Object> detail) {
        append(type, message, documentId, detail);
        logger.info("{} - documentId={} detail={}", message, documentId, detail);
    }

    public void error(String type, String message, String documentId, Map<String, Object> detail) {
        append(type, message, documentId, detail);
        logger.error("{} - documentId={} detail={}", message, documentId, detail);
    }

    public List<RagOperationLog> latest(int limit) {
        return operationLogRepository.latest(limit);
    }

    private void append(String type, String message, String documentId, Map<String, Object> detail) {
        RagOperationLog log = new RagOperationLog();
        log.setId(UUID.randomUUID().toString());
        log.setType(type);
        log.setMessage(message);
        log.setDocumentId(documentId);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        operationLogRepository.append(log);
    }
}
