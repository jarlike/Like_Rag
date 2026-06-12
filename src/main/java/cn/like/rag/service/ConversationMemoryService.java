package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话会话记忆：进程内存储，带<b>硬限制</b>——单会话最大轮数、历史文本字符上限、单条回答存储上限、
 * 会话空闲 TTL、最大会话数（超出按最久未访问淘汰）。对应《重排序与多轮对话项目书》第四章。
 *
 * <p>仅用于多轮问答的上下文衔接与追问改写，<b>不落库</b>，重启即清空，也不参与答案的事实依据。
 * 所有限制项可通过 {@code rag.conversation.*} 配置；任一限制都为了防止内存无界增长与 prompt 膨胀。
 */
@Service
public class ConversationMemoryService {

    private final RagProperties properties;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public ConversationMemoryService(RagProperties properties) {
        this.properties = properties;
    }

    /** 会话是否启用且 sessionId 有效。 */
    public boolean isActive(String sessionId) {
        return properties.getConversation().isEnabled() && sessionId != null && !sessionId.isBlank();
    }

    /**
     * 取该会话最近若干轮历史（不超过 maxTurns，且已剔除过期会话）。无历史返回空列表。
     */
    public List<Turn> recentTurns(String sessionId) {
        if (!isActive(sessionId)) {
            return List.of();
        }
        purgeExpired();
        Session session = sessions.get(sessionId);
        if (session == null) {
            return List.of();
        }
        long now = now();
        synchronized (session) {
            if (isExpired(session, now)) {
                sessions.remove(sessionId);
                return List.of();
            }
            session.lastAccess = now;
            return new ArrayList<>(session.turns);
        }
    }

    /**
     * 把历史拼成喂给 LLM 的文本（最近优先），总长度不超过 maxHistoryChars。无历史返回空串。
     */
    public String historyText(String sessionId) {
        List<Turn> turns = recentTurns(sessionId);
        if (turns.isEmpty()) {
            return "";
        }
        int maxChars = Math.max(0, properties.getConversation().getMaxHistoryChars());
        StringBuilder sb = new StringBuilder();
        // 从最近一轮往前拼，超过预算即停，保证保留的是离当前问题最近的上下文。
        for (int i = turns.size() - 1; i >= 0; i--) {
            Turn t = turns.get(i);
            String block = "用户：" + t.getQuestion() + "\n助手：" + t.getAnswer() + "\n";
            if (sb.length() + block.length() > maxChars && sb.length() > 0) {
                break;
            }
            sb.insert(0, block);
        }
        return sb.toString().trim();
    }

    /**
     * 记录一轮问答。会截断过长回答、丢弃超出 maxTurns 的最旧轮，并在会话数超限时淘汰最久未访问会话。
     */
    public void record(String sessionId, String question, String answer) {
        if (!isActive(sessionId) || question == null || answer == null) {
            return;
        }
        RagProperties.Conversation cfg = properties.getConversation();
        long now = now();
        purgeExpired();

        Session session = sessions.computeIfAbsent(sessionId, k -> new Session());
        synchronized (session) {
            session.lastAccess = now;
            session.turns.add(new Turn(truncate(question, cfg.getMaxStoredAnswerChars()),
                    truncate(answer, cfg.getMaxStoredAnswerChars()), now));
            int maxTurns = Math.max(1, cfg.getMaxTurns());
            while (session.turns.size() > maxTurns) {
                session.turns.remove(0);
            }
        }
        enforceMaxSessions(cfg.getMaxSessions());
    }

    /** 清空某会话（如用户主动重置对话）。 */
    public void clear(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public int sessionCount() {
        return sessions.size();
    }

    private void enforceMaxSessions(int maxSessions) {
        int max = Math.max(1, maxSessions);
        while (sessions.size() > max) {
            String oldest = null;
            long oldestAccess = Long.MAX_VALUE;
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                long access = entry.getValue().lastAccess;
                if (access < oldestAccess) {
                    oldestAccess = access;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            sessions.remove(oldest);
        }
    }

    private void purgeExpired() {
        long now = now();
        sessions.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private boolean isExpired(Session session, long now) {
        long ttlMillis = Math.max(0, properties.getConversation().getSessionTtlSeconds()) * 1000L;
        if (ttlMillis <= 0) {
            return false;
        }
        return now - session.lastAccess > ttlMillis;
    }

    private static String truncate(String text, int maxChars) {
        int max = Math.max(1, maxChars);
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "…";
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static final class Session {
        private final List<Turn> turns = new ArrayList<>();
        private volatile long lastAccess = now();
    }

    /** 一轮问答（已截断、规整后的存储形态）。 */
    public static final class Turn {
        private final String question;
        private final String answer;
        private final long createdAt;

        public Turn(String question, String answer, long createdAt) {
            this.question = question;
            this.answer = answer;
            this.createdAt = createdAt;
        }

        public String getQuestion() {
            return question;
        }

        public String getAnswer() {
            return answer;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }
}
