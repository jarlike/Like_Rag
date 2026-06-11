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

    @Test
    void shouldNotMixHeadingTextIntoChunks() {
        RagProperties properties = new RagProperties();
        properties.setChunkMaxTokens(40);
        properties.setChunkOverlapTokens(0);
        Chunker chunker = new Chunker(properties);

        List<Chunker.ChunkDraft> chunks = chunker.splitWithMetadata("""
                # 项目介绍
                第一段说明系统会解析文档并写入向量库。

                ## 检索流程
                第二段说明问题会先做向量检索，再把相似度最高的片段交给模型。
                """);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("项目介绍");
        assertThat(chunks.get(0).text()).doesNotContain("# 项目介绍");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("项目介绍 > 检索流程");
        assertThat(chunks.get(1).text()).doesNotContain("## 检索流程");
    }

    @Test
    void shouldNormalizeHtmlBeforeChunking() {
        RagProperties properties = new RagProperties();
        properties.setChunkMaxTokens(30);
        properties.setChunkOverlapTokens(0);
        Chunker chunker = new Chunker(properties);

        List<Chunker.ChunkDraft> chunks = chunker.splitWithMetadata("""
                <h1>索引流程</h1>
                <p>上传文件后解析文本。</p>
                <p>随后按相似度召回切片。</p>
                """);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).sectionPath()).isEqualTo("索引流程");
        assertThat(chunks.get(0).text()).contains("上传文件后解析文本");
        assertThat(chunks.get(0).text()).doesNotContain("<p>");
    }

    @Test
    void shouldKeepLargeParagraphsWithinTokenLimit() {
        RagProperties properties = new RagProperties();
        properties.setChunkMaxTokens(12);
        properties.setChunkOverlapTokens(0);
        Chunker chunker = new Chunker(properties);

        List<Chunker.ChunkDraft> chunks = chunker.splitWithMetadata("这是第一句内容。这里是第二句内容。这里是第三句内容。这里是第四句内容。");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(12));
    }
}
