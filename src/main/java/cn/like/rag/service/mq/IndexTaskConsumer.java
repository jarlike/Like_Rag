package cn.like.rag.service.mq;

import cn.like.rag.model.IndexMessage;
import cn.like.rag.service.IndexingService;
import cn.like.rag.service.OperationLogService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "rag.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${rag.mq.topic}",
        consumerGroup = "${rag.mq.consumer-group}"
)
public class IndexTaskConsumer implements RocketMQListener<IndexMessage> {

    private final IndexingService indexingService;
    private final OperationLogService operationLogService;

    public IndexTaskConsumer(IndexingService indexingService, OperationLogService operationLogService) {
        this.indexingService = indexingService;
        this.operationLogService = operationLogService;
    }

    @Override
    public void onMessage(IndexMessage message) {
        operationLogService.info("MQ_RECEIVED", "Index message received from RocketMQ", message.getDocumentId(),
                Map.of("rebuild", message.isRebuild()));
        indexingService.indexDocument(message.getDocumentId(), message.isRebuild());
    }
}
