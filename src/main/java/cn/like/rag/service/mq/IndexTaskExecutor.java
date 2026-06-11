package cn.like.rag.service.mq;

import cn.like.rag.model.IndexMessage;
import cn.like.rag.service.IndexingService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class IndexTaskExecutor {

    private final IndexingService indexingService;

    public IndexTaskExecutor(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @Async
    public void execute(IndexMessage message) {
        indexingService.indexDocument(message.getDocumentId(), message.isRebuild());
    }
}
