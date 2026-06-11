package cn.like.rag.service.mq;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.IndexMessage;
import cn.like.rag.service.OperationLogService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class IndexTaskPublisher {

    private final RagProperties properties;
    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final IndexTaskExecutor indexTaskExecutor;
    private final OperationLogService operationLogService;

    public IndexTaskPublisher(RagProperties properties,
                              ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider,
                              IndexTaskExecutor indexTaskExecutor,
                              OperationLogService operationLogService) {
        this.properties = properties;
        this.rocketMQTemplateProvider = rocketMQTemplateProvider;
        this.indexTaskExecutor = indexTaskExecutor;
        this.operationLogService = operationLogService;
    }

    public void publish(IndexMessage message) {
        if (!properties.getMq().isEnabled()) {
            operationLogService.info("MQ_DISABLED", "RocketMQ disabled, use local async indexing", message.getDocumentId(), Map.of());
            fallback(message);
            return;
        }
        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        if (rocketMQTemplate == null) {
            operationLogService.error("MQ_TEMPLATE_MISSING", "RocketMQTemplate missing, use local async indexing",
                    message.getDocumentId(), Map.of());
            fallback(message);
            return;
        }
        try {
            rocketMQTemplate.convertAndSend(properties.getMq().getTopic(), message);
            operationLogService.info("MQ_SENT", "Index message sent to RocketMQ", message.getDocumentId(),
                    Map.of("topic", properties.getMq().getTopic(), "rebuild", message.isRebuild()));
        } catch (Exception e) {
            operationLogService.error("MQ_SEND_FAILED", "RocketMQ send failed, use local async indexing", message.getDocumentId(),
                    Map.of("error", e.getMessage()));
            if (!properties.isAsyncIndexFallback()) {
                throw e;
            }
            fallback(message);
        }
    }

    private void fallback(IndexMessage message) {
        indexTaskExecutor.execute(message);
    }
}
