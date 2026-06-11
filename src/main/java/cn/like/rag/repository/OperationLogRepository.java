package cn.like.rag.repository;

import cn.like.rag.config.RagProperties;
import cn.like.rag.model.RagOperationLog;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Repository
public class OperationLogRepository {

    private static final int MAX_LOGS = 500;

    private final JsonFileStore jsonFileStore;
    private final Path file;
    private final List<RagOperationLog> logs = new ArrayList<>();

    public OperationLogRepository(JsonFileStore jsonFileStore, RagProperties properties) {
        this.jsonFileStore = jsonFileStore;
        this.file = Path.of(properties.getStorageRoot(), "operation-logs.json");
    }

    @PostConstruct
    public synchronized void init() {
        LogSnapshot snapshot = jsonFileStore.read(file, LogSnapshot.class);
        if (snapshot != null && snapshot.logs != null) {
            logs.addAll(snapshot.logs);
        }
    }

    public synchronized RagOperationLog append(RagOperationLog log) {
        logs.add(log);
        logs.sort(Comparator.comparing(RagOperationLog::getCreatedAt).reversed());
        if (logs.size() > MAX_LOGS) {
            logs.subList(MAX_LOGS, logs.size()).clear();
        }
        flush();
        return log;
    }

    public synchronized List<RagOperationLog> latest(int limit) {
        return logs.stream().limit(Math.max(1, limit)).toList();
    }

    private void flush() {
        LogSnapshot snapshot = new LogSnapshot();
        snapshot.logs = new ArrayList<>(logs);
        jsonFileStore.write(file, snapshot);
    }

    public static class LogSnapshot {
        public List<RagOperationLog> logs = new ArrayList<>();
    }
}
