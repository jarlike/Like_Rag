package cn.like.rag.service;

import cn.like.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    @Test
    void shouldSplitByHeadingAndParagraph() {
        RagProperties properties = new RagProperties();
        properties.setChunkMaxTokens(30);
        properties.setChunkOverlapTokens(5);
        Chunker chunker = new Chunker(properties);

        List<Chunker.ChunkDraft> chunks = chunker.splitWithMetadata("""
                # 第一章
                这是第一段内容，专门用于测试切片。

                这是第二段内容，继续说明系统如何工作。

                ## 第二节
                这里是另一段内容，应该带着新的章节路径。
                """);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).sectionPath()).contains("第一章");
        assertThat(chunks.stream().map(Chunker.ChunkDraft::text)).anyMatch(text -> text.contains("第二段内容"));
    }
}
