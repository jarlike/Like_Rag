package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证多轮会话记忆的硬限制：最大轮数、最大会话数、单条回答截断、历史文本字符上限、
 * 以及开关/无效 sessionId 的降级行为。
 */
class ConversationMemoryServiceTest {

    private RagProperties props() {
        return new RagProperties();
    }

    @Test
    void keepsOnlyMaxTurnsMostRecent() {
        RagProperties p = props();
        p.getConversation().setMaxTurns(2);
        ConversationMemoryService svc = new ConversationMemoryService(p);

        svc.record("s1", "q1", "a1");
        svc.record("s1", "q2", "a2");
        svc.record("s1", "q3", "a3");

        List<ConversationMemoryService.Turn> turns = svc.recentTurns("s1");
        assertThat(turns).hasSize(2);
        assertThat(turns).extracting(ConversationMemoryService.Turn::getQuestion)
                .containsExactly("q2", "q3"); // 最旧的 q1 被丢弃，按时间顺序返回
    }

    @Test
    void disabledConversationIsNoOp() {
        RagProperties p = props();
        p.getConversation().setEnabled(false);
        ConversationMemoryService svc = new ConversationMemoryService(p);

        svc.record("s1", "q1", "a1");

        assertThat(svc.isActive("s1")).isFalse();
        assertThat(svc.recentTurns("s1")).isEmpty();
        assertThat(svc.historyText("s1")).isEmpty();
    }

    @Test
    void blankSessionIdIsInactive() {
        ConversationMemoryService svc = new ConversationMemoryService(props());
        assertThat(svc.isActive(null)).isFalse();
        assertThat(svc.isActive("  ")).isFalse();
        assertThat(svc.historyText(" ")).isEmpty();
        assertThat(svc.recentTurns(null)).isEmpty();
    }

    @Test
    void evictsOldestWhenExceedingMaxSessions() {
        RagProperties p = props();
        p.getConversation().setMaxSessions(2);
        ConversationMemoryService svc = new ConversationMemoryService(p);

        svc.record("a", "q", "a");
        svc.record("b", "q", "a");
        svc.record("c", "q", "a");

        assertThat(svc.sessionCount()).isLessThanOrEqualTo(2);
        // 最久未访问的会话 a 被淘汰，最新的 c 保留
        assertThat(svc.recentTurns("c")).hasSize(1);
        assertThat(svc.recentTurns("a")).isEmpty();
    }

    @Test
    void truncatesStoredAnswerToLimit() {
        RagProperties p = props();
        p.getConversation().setMaxStoredAnswerChars(5);
        ConversationMemoryService svc = new ConversationMemoryService(p);

        svc.record("s1", "q", "123456789");

        String stored = svc.recentTurns("s1").get(0).getAnswer();
        assertThat(stored).startsWith("12345");
        assertThat(stored.length()).isLessThanOrEqualTo(6); // 5 chars + 省略号
    }

    @Test
    void historyTextRespectsMaxCharsAndKeepsMostRecent() {
        RagProperties p = props();
        p.getConversation().setMaxTurns(10);
        p.getConversation().setMaxHistoryChars(15); // 仅够容纳最近一轮
        ConversationMemoryService svc = new ConversationMemoryService(p);

        svc.record("s1", "q1", "a1");
        svc.record("s1", "q2", "a2");

        String text = svc.historyText("s1");
        assertThat(text).contains("q2");
        assertThat(text).doesNotContain("q1"); // 超出字符预算的较旧一轮被丢弃
    }
}
