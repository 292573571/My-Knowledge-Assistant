package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RagServiceLearningRecordTest {

    @Test
    void retrievesOriginalAndStandaloneQuestionsAndKeepsHistoryOutOfPromptText() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        ConversationMemory memory = new ConversationMemory();
        memory.addUserMessage("conversation-1", "VPN 客户端是什么？");
        memory.addAssistantMessage("conversation-1", "它用于连接公司网络。");
        String question = "它支持什么操作系统版本？";
        String standalone = "VPN 客户端支持什么操作系统版本？";
        SourceDocument source = new SourceDocument(
                "vpn-1", "VPN 客户端支持 macOS 10.13 及以上版本。", "系统要求", "vpn.pdf",
                "docs/vpn.pdf", 0).withScore(0.1);
        when(chatClient.generate(Mockito.anyString(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("RELATED: " + standalone);
        when(vectorStore.similaritySearch(question, 15)).thenReturn(List.of(source));
        when(vectorStore.similaritySearch(standalone, 15)).thenReturn(List.of(source));
        when(chatClient.call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("VPN 客户端支持 macOS 10.13 及以上版本。");
        RagQualityGate qualityGate = Mockito.mock(RagQualityGate.class);
        when(qualityGate.relevantSources(Mockito.anyString(), Mockito.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qualityGate.approvesAnswer(Mockito.anyString(), Mockito.anyString(), Mockito.anyList())).thenReturn(true);
        RagService service = new RagService(
                Mockito.mock(DocumentIngestionService.class), vectorStore, chatClient, memory,
                Mockito.mock(WebSearchService.class), qualityGate, false, 5, 0.45, "distance",
                false, false, 4, true, false);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", question));

        assertThat(response.answer()).contains("macOS 10.13");
        verify(vectorStore).similaritySearch(question, 15);
        verify(vectorStore).similaritySearch(standalone, 15);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.example.workbench.memory.ChatMessage>> history = ArgumentCaptor.forClass(List.class);
        verify(chatClient).call(prompt.capture(), Mockito.anyList(), history.capture(), Mockito.anyMap());
        assertThat(prompt.getValue()).doesNotContain("user:", "assistant:", "VPN 客户端是什么？");
        assertThat(history.getValue()).extracting(com.example.workbench.memory.ChatMessage::role)
                .containsExactly("user", "assistant");
    }

    @Test
    void answersLearningAssistantIntroductionWithoutModelOrRetrieval() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "你是干嘛的？"));

        assertThat(response.answer())
                .contains("您好，我是您的 AI 学习助理。")
                .contains("理解和梳理学习中的知识点")
                .contains("协助回顾和复习已学内容");
        assertThat(response.sources()).isEmpty();
        verify(vectorStore, never()).similaritySearch(Mockito.anyString(), Mockito.anyInt());
        verify(chatClient, never()).generate(Mockito.anyString());
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    @Test
    void answersPlainGreetingWithTheFixedAssistantIntroduction() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "你好！"));

        assertThat(response.answer()).isEqualTo("""
                您好，我是您的 AI 学习助理。

                我可以帮助您：
                - 理解和梳理学习中的知识点
                - 基于已导入资料进行知识库问答
                - 在资料不足时提供明确标注的通用知识补充
                - 自动沉淀每日学习记录
                - 协助回顾和复习已学内容
                """);
        verify(vectorStore, never()).similaritySearch(Mockito.anyString(), Mockito.anyInt());
        verify(chatClient, never()).generate(Mockito.anyString());
    }

    @Test
    void answersPinyinGreetingWithTheFixedAssistantIntroduction() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        RagService service = service(vectorStore, chatClient);

        RagChatResponse compact = service.chat(new RagChatRequest("conversation-1", "nihao"));
        RagChatResponse spaced = service.chat(new RagChatRequest("conversation-2", "nin hao!"));

        assertThat(compact.answer()).contains("您好，我是您的 AI 学习助理。");
        assertThat(spaced.answer()).contains("您好，我是您的 AI 学习助理。");
        verify(vectorStore, never()).similaritySearch(Mockito.anyString(), Mockito.anyInt());
        verify(chatClient, never()).generate(Mockito.anyString());
    }

    @Test
    void doesNotTreatGreetingWithARealQuestionAsIntroductionOnly() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(vectorStore.similaritySearch("你好，RAG 是什么？", 15)).thenReturn(List.of());
        when(chatClient.generate(Mockito.anyString())).thenReturn("RAG 是一种结合信息检索与模型生成的技术。 ");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "你好，RAG 是什么？"));

        assertThat(response.answer()).contains("通用大模型知识").contains("信息检索");
        verify(vectorStore).similaritySearch("你好，RAG 是什么？", 15);
    }

    @Test
    void keepsRelevantPdfPageAtCalibratedChromaDistance() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument pdfPage = new SourceDocument(
                "vpn#chunk-0", "VPN客户端只支持：MacOS 10.13及以上版本", "", "vpn.pdf",
                "docs/workspaces/personal-1/vpn.pdf", 0, "vpn", "vpn.pdf", "hash",
                0.6392584, "", 0, 0, 30, "pdf-page", "SOURCE", "1",
                "personal-1", com.example.workbench.workspace.DocumentVisibility.PRIVATE, 1
        );
        when(vectorStore.similaritySearch("VPN客户端支持什么操作系统版本？", 60)).thenReturn(List.of(pdfPage));
        when(chatClient.call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("VPN客户端支持 MacOS 10.13 及以上版本。");
        RagService service = service(vectorStore, chatClient, 1.0);

        RagChatResponse response = service.chat(new RagChatRequest(
                "user-1:conversation-1", "personal-1", "VPN客户端支持什么操作系统版本？"));

        assertThat(response.answer()).contains("MacOS 10.13");
        assertThat(response.sources()).singleElement().satisfies(source -> {
            assertThat(source.file()).isEqualTo("vpn.pdf");
            assertThat(source.pageNumber()).isEqualTo(1);
        });
        verify(chatClient, never()).generate(Mockito.anyString());
    }

    @Test
    void reranksSubstantiveBodyBeforeLimitingCandidates() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        List<SourceDocument> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            String heading = "Kettle 标题 " + index;
            candidates.add(new SourceDocument(
                    "heading-" + index, heading, "", "kettle.docx", "docs/kettle.docx", index,
                    "kettle", "kettle.docx", "hash", 0.1 + index * 0.01, heading,
                    1, 0, heading.length(), "docx-heading", "SOURCE", "",
                    "public-default", com.example.workbench.workspace.DocumentVisibility.PUBLIC, 0
            ));
        }
        SourceDocument body = new SourceDocument(
                "body", "Kettle 是一款开源的数据集成工具，主要用于数据抽取、转换和加载。", "",
                "kettle.docx", "docs/kettle.docx", 5, "kettle", "kettle.docx", "hash",
                0.6, "Kettle 入门", 1, 100, 160, "docx-section", "SOURCE", "",
                "public-default", com.example.workbench.workspace.DocumentVisibility.PUBLIC, 0
        );
        candidates.add(body);
        when(vectorStore.similaritySearch("Kettle是啥？", 15)).thenReturn(candidates);
        when(chatClient.call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("Kettle 是一款开源的数据集成工具。");
        RagService service = service(vectorStore, chatClient, 1.0);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "Kettle是啥？"));

        assertThat(response.answer()).contains("数据集成工具");
        assertThat(response.sources()).extracting(RagSource::chunkIndex).contains(5);
    }

    @Test
    void rejectsPdfPageAboveCalibratedChromaDistance() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument unrelatedPdfPage = new SourceDocument(
                "vpn#chunk-3", "勾选完全磁盘访问权限。", "", "vpn.pdf",
                "docs/workspaces/personal-1/vpn.pdf", 3, "vpn", "vpn.pdf", "hash",
                1.3449115, "", 0, 0, 20, "pdf-page", "SOURCE", "1",
                "personal-1", com.example.workbench.workspace.DocumentVisibility.PRIVATE, 4
        );
        when(vectorStore.similaritySearch("红烧肉应该怎么制作？", 60)).thenReturn(List.of(unrelatedPdfPage));
        when(chatClient.generate(Mockito.anyString())).thenReturn("红烧肉可以通过焯水、炒糖色和炖煮制作。");
        RagService service = service(vectorStore, chatClient, 1.0);

        RagChatResponse response = service.chat(new RagChatRequest(
                "user-1:conversation-1", "personal-1", "红烧肉应该怎么制作？"));

        assertThat(response.sources()).isEmpty();
        assertThat(response.answer()).contains("通用大模型知识").doesNotContain("完全磁盘访问权限");
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    @Test
    void usesModelFallbackWhenRetrievedDocumentsMissExplicitTechnicalAcronym() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument unrelatedKettleChunk = new SourceDocument(
                "kettle-1", "Kettle 转换控件用于完成 ETL 数据处理。", "Kettle 工具简介",
                "kettle.docx", "docs/kettle.docx", 1, "kettle", "kettle.docx", "hash",
                0.2, "功能介绍 > Kettle 的转换", 2, 0, 30, "docx-section", "SOURCE", "",
                "public-default", com.example.workbench.workspace.DocumentVisibility.PUBLIC, 0
        );
        String question = "在AI应用中SSL是啥意思？";
        when(vectorStore.similaritySearch(question, 15)).thenReturn(List.of(unrelatedKettleChunk));
        when(chatClient.generate(Mockito.anyString())).thenReturn("SSL 是一种用于保护网络通信的安全协议。");
        RagService service = service(vectorStore, chatClient, 1.0);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", question));

        assertThat(response.answer()).contains("SSL 是").contains("通用大模型知识");
        assertThat(response.sources()).isEmpty();
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    @Test
    void streamsModelFallbackWithoutSourcesWhenDocumentsMissExplicitTechnicalAcronym() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument unrelatedKettleChunk = new SourceDocument(
                "kettle-1", "Kettle 转换控件用于完成 ETL 数据处理。", "Kettle 工具简介",
                "kettle.docx", "docs/kettle.docx", 1, "kettle", "kettle.docx", "hash",
                0.2, "功能介绍 > Kettle 的转换", 2, 0, 30, "docx-section", "SOURCE", "",
                "public-default", com.example.workbench.workspace.DocumentVisibility.PUBLIC, 0
        );
        String question = "在AI应用中SSL是啥意思？";
        when(vectorStore.similaritySearch(question, 15)).thenReturn(List.of(unrelatedKettleChunk));
        when(chatClient.stream(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(reactor.core.publisher.Flux.just("SSL 是网络安全协议。"));
        RagService service = service(vectorStore, chatClient, 1.0);

        RagStreamResponse response = service.stream(new RagChatRequest("conversation-1", question));

        assertThat(response.tokens().collectList().block())
                .containsExactly("SSL 是网络安全协议。", "\n\n以上回答基于通用大模型知识，不是当前知识库内容。");
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void skipsSynchronousSourceQualityGateForStreamingAnswers() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        RagQualityGate qualityGate = Mockito.mock(RagQualityGate.class);
        SourceDocument source = new SourceDocument(
                "ssl-1", "SSL 用于保护网络通信。", "网络安全", "security.pdf", "docs/security.pdf", 1
        ).withScore(0.2);
        when(vectorStore.similaritySearch("SSL 是什么？", 15)).thenReturn(List.of(source));
        when(chatClient.stream(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(reactor.core.publisher.Flux.just("SSL 是网络安全协议。"));
        RagService service = new RagService(
                Mockito.mock(DocumentIngestionService.class), vectorStore, chatClient, new ConversationMemory(),
                Mockito.mock(WebSearchService.class), qualityGate, true, 5, 0.45, "distance",
                false, false, 4, true, false
        );

        RagStreamResponse response = service.stream(new RagChatRequest("conversation-1", "SSL 是什么？"));

        assertThat(response.tokens().collectList().block()).containsExactly("SSL 是网络安全协议。");
        verify(qualityGate, never()).relevantSources(Mockito.anyString(), Mockito.anyList());
    }

    @Test
    void retriesModelFallbackWhenCodeContainsTranslatedIdentifierFragments() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        String question = "PostgreSQL 查询当前库所有表名";
        when(vectorStore.similaritySearch(question, 15)).thenReturn(List.of());
        when(chatClient.generate(Mockito.anyString()))
                .thenReturn("```sql\nSELECT table_name\nFROM information,\n信息_schema.tables;")
                .thenReturn("```sql\nSELECT schemaname, tablename FROM pg_catalog.pg_tables "
                        + "WHERE schemaname NOT IN ('pg_catalog', 'information_schema');\n```");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", question));

        assertThat(response.answer())
                .contains("pg_catalog.pg_tables")
                .doesNotContain("信息_schema");
        verify(chatClient, times(2)).generate(Mockito.anyString());
    }

    @Test
    void excludesAutomaticLearningRecordsFromNormalQuestions() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument learningRecord = new SourceDocument(
                "record-1",
                "你好",
                "2026-07-26 学习记录",
                "2026-07-26.md",
                "docs/learning-records/user-alice/2026-07-26.md",
                0
        ).withScore(0.1);
        when(vectorStore.similaritySearch("今天适合学习什么？", 5)).thenReturn(List.of(learningRecord));
        when(chatClient.generate(Mockito.anyString())).thenReturn("我是个人学习知识库助手。");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "今天适合学习什么？"));

        assertThat(response.answer()).contains("通用大模型知识");
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void retriesModelFallbackOnceWhenTheFirstAnswerContainsGibberish() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(vectorStore.similaritySearch("今天适合学习什么？", 5)).thenReturn(List.of());
        when(chatClient.generate(Mockito.anyString()))
                .thenReturn("我是你的学习助理 kukuiukuiu")
                .thenReturn("建议先选择一个明确主题，安排短时间的阅读和练习。");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "今天适合学习什么？"));

        assertThat(response.answer()).contains("通用大模型知识").contains("建议先选择一个明确主题");
        verify(chatClient, times(2)).generate(Mockito.anyString());
    }

    @Test
    void acceptsNormalLongTechnicalTermsInModelFallbackAnswers() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(vectorStore.similaritySearch("Spring AI 如何实现 RAG？", 5)).thenReturn(List.of());
        when(chatClient.generate(Mockito.anyString())).thenReturn(
                "Spring AI 可以通过 QuestionAnswerAdvisor 和 RetrievalAugmentationAdvisor 组合向量检索与模型生成。"
        );
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "Spring AI 如何实现 RAG？"));

        assertThat(response.answer())
                .contains("通用大模型知识")
                .contains("RetrievalAugmentationAdvisor");
        verify(chatClient, times(1)).generate(Mockito.anyString());
    }

    @Test
    void returnsSafetyAnswerWhenBothModelFallbackAnswersAreInvalid() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(vectorStore.similaritySearch("今天适合学习什么？", 5)).thenReturn(List.of());
        when(chatClient.generate(Mockito.anyString())).thenReturn("kukuiukuiu");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "今天适合学习什么？"));

        assertThat(response.answer()).isEqualTo("当前知识库没有相关资料，且模型回退调用未能成功。请检查模型配置是否正确（API 地址、API Key、模型标识），或稍后重试。");
        verify(chatClient, times(2)).generate(Mockito.anyString());
    }

    @Test
    void doesNotRetryModelFallbackWhenProviderReturnsNoContent() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        when(vectorStore.similaritySearch("今天适合学习什么？", 5)).thenReturn(List.of());
        when(chatClient.generate(Mockito.anyString())).thenReturn(null);
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "今天适合学习什么？"));

        assertThat(response.answer()).isEqualTo("当前知识库没有相关资料，且模型回退调用未能成功。请检查模型配置是否正确（API 地址、API Key、模型标识），或稍后重试。");
        verify(chatClient, times(1)).generate(Mockito.anyString());
    }

    @Test
    void ignoresHeadingOnlySourcesAndFallsBackToTheModel() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument headingOnly = new SourceDocument(
                "rag-heading",
                "# RAG",
                "RAG",
                "rag.md",
                "docs/rag.md",
                0
        ).withScore(0.29);
        when(vectorStore.similaritySearch("RAG是什么？", 5)).thenReturn(List.of(headingOnly));
        when(chatClient.generate(Mockito.anyString())).thenReturn("RAG 是一种先检索相关资料，再结合资料生成回答的技术。它可以让模型回答更有依据。");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "RAG是什么？"));

        assertThat(response.answer())
                .contains("通用大模型知识")
                .contains("先检索相关资料");
        assertThat(response.sources()).isEmpty();
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    @Test
    void doesNotUseAnUnrelatedRagNoteToAnswerAnEmbeddingQuestion() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument unrelatedNote = new SourceDocument(
                "note-1",
                "RAG 是一种先检索相关资料，再结合资料生成回答的技术。",
                "2026-07-27 正式笔记",
                "2026-07-27-learning-note.md",
                "docs/manual-notes/user-1/2026-07-27-learning-note.md",
                7,
                "note-document-1",
                "2026-07-27-learning-note.md",
                "hash",
                0.1,
                "2026-07-27 学习记录 > 回答",
                2,
                0,
                30,
                "markdown-section",
                "FORMAL_NOTE",
                "1"
        );
        String retrievalQuery = "embedding是什么？\nEmbedding 向量表示 语义表示";
        when(vectorStore.similaritySearch(retrievalQuery, 60)).thenReturn(List.of(unrelatedNote));
        when(chatClient.generate(Mockito.anyString())).thenReturn("Embedding 是将文本等对象映射为数值向量的表示方法。");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("user-1:conversation-1", "embedding是什么？"));

        assertThat(response.answer()).contains("Embedding 是").doesNotContain("RAG 是一种先检索");
        assertThat(response.sources()).isEmpty();
        verify(vectorStore).similaritySearch(retrievalQuery, 60);
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    @Test
    void usesTheModelWhenTheOnlyMcpMatchIsAnOldModelGeneratedAnswer() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument oldModelAnswer = new SourceDocument(
                "mcp-note-1",
                "MCP 是模型上下文协议。\n\n以上回答基于通用大模型知识，不是当前知识库内容。",
                "MCP 学习笔记",
                "mcp-learning-note.md",
                "docs/manual-notes/user-1/mcp-learning-note.md",
                0
        ).withScore(0.1);
        String retrievalQuery = "MCP是什么？\nMCP Model Context Protocol 基本概念 标准协议 外部工具 数据源 资源服务";
        when(vectorStore.similaritySearch(retrievalQuery, 20)).thenReturn(List.of(oldModelAnswer));
        when(chatClient.generate(Mockito.anyString())).thenReturn("MCP 是 Model Context Protocol，用于让 AI 应用以统一方式连接外部工具和数据源。");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("user-1:conversation-1", "MCP是什么？"));

        assertThat(response.answer()).contains("Model Context Protocol").contains("通用大模型知识");
        assertThat(response.sources()).isEmpty();
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
        verify(chatClient).generate(Mockito.anyString());
    }

    @Test
    void usesTheModelWhenTheOnlyMcpMatchIsAPromotedLearningNoteQuestion() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument promotedLearningQuestion = new SourceDocument(
                "mcp-question-1",
                "MCP是什么？",
                "2026-07-28 正式笔记",
                "2026-07-28-learning-note.md",
                "docs/manual-notes/user-1/2026-07-28-learning-note.md",
                1,
                "mcp-note-1",
                "2026-07-28-learning-note.md",
                "hash",
                0.1,
                "2026-07-28 学习记录 > 问题",
                2,
                0,
                8,
                "markdown-section",
                "FORMAL_NOTE",
                "1"
        );
        String retrievalQuery = "MCP是什么？\nMCP Model Context Protocol 基本概念 标准协议 外部工具 数据源 资源服务";
        when(vectorStore.similaritySearch(retrievalQuery, 20)).thenReturn(List.of(promotedLearningQuestion));
        when(chatClient.generate(Mockito.anyString())).thenReturn("MCP 是 Model Context Protocol，用于让 AI 应用以统一方式连接外部工具和数据源。");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("user-1:conversation-1", "MCP是什么？"));

        assertThat(response.answer()).contains("Model Context Protocol").contains("通用大模型知识");
        assertThat(response.sources()).isEmpty();
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    @Test
    void fallsBackToGeneralKnowledgeInsteadOfEchoingContextWhenGroundingFails() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        RagQualityGate qualityGate = Mockito.mock(RagQualityGate.class);
        SourceDocument source = new SourceDocument(
                "embedding-1",
                "Embedding 用数值向量表示文本的语义。",
                "Embedding",
                "embedding.md",
                "docs/embedding.md",
                0
        ).withScore(0.1);
        String retrievalQuery = "embedding是什么？\nEmbedding 向量表示 语义表示";
        when(vectorStore.similaritySearch(retrievalQuery, 5)).thenReturn(List.of(source));
        when(chatClient.call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap()))
                .thenReturn("这是一段没有依据的回答。");
        when(qualityGate.approvesAnswer(Mockito.anyString(), Mockito.anyString(), Mockito.anyList())).thenReturn(false);
        when(chatClient.generate(Mockito.anyString())).thenReturn("Embedding 是将对象映射为数值向量的技术。");
        RagService service = service(vectorStore, chatClient, qualityGate);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "embedding是什么？"));

        assertThat(response.answer()).contains("Embedding 是将对象映射").contains("通用大模型知识");
        assertThat(response.sources()).isEmpty();
        assertThat(response.answer()).doesNotContain("Embedding 用数值向量表示文本的语义");
    }

    @Test
    void excludesLearningRecordsEvenForExplicitReviewQuestions() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument learningRecord = new SourceDocument(
                "record-1",
                "RAG 先检索再生成。",
                "2026-07-26 学习记录",
                "2026-07-26.md",
                "docs/learning-records/user-alice/2026-07-26.md",
                0
        ).withScore(0.1);
        when(vectorStore.similaritySearch("我之前学过什么？", 5)).thenReturn(List.of(learningRecord));
        when(chatClient.generate(Mockito.anyString())).thenReturn("你可以在学习记录页面查看尚未整理的每日记录。 ");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-1", "我之前学过什么？"));

        assertThat(response.sources()).isEmpty();
        assertThat(response.answer()).contains("通用大模型知识");
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    @Test
    void excludesAnotherUsersLearningRecordsFromReviewQuestions() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument anotherUsersRecord = new SourceDocument(
                "record-2",
                "Bob 学习了数据库索引。",
                "2026-07-26 学习记录",
                "2026-07-26.md",
                "docs/learning-records/user-2/2026-07-26.md",
                0
        ).withScore(0.1);
        when(vectorStore.similaritySearch("我之前学过什么？", 20)).thenReturn(List.of(anotherUsersRecord));
        when(chatClient.generate(Mockito.anyString())).thenReturn("暂时没有找到你自己的学习记录，可以先完成一次学习问答。 ");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("user-1:conversation-1", "我之前学过什么？"));

        assertThat(response.sources()).isEmpty();
        assertThat(response.answer()).contains("通用大模型知识");
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    @Test
    void doesNotExposePrivateSourcesWhenUserScopeIsMissing() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        LocalChatClient chatClient = Mockito.mock(LocalChatClient.class);
        SourceDocument privateSource = new SourceDocument(
                "private-1", "Bob 的私有数据库设计。", "私有笔记", "private.md",
                "docs/manual-notes/user-2/private.md", 0, "private-doc", "private.md", "hash",
                0.9, "# 私有笔记", 1, 0, 12, "text-paragraph", "FORMAL_NOTE", "2"
        );
        when(vectorStore.similaritySearch(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of(privateSource));
        when(chatClient.generate(Mockito.anyString())).thenReturn("当前没有可用的公共知识内容。");
        RagService service = service(vectorStore, chatClient);

        RagChatResponse response = service.chat(new RagChatRequest("conversation-without-user-scope", "数据库如何设计？"));

        assertThat(response.sources()).isEmpty();
        assertThat(response.answer()).doesNotContain("Bob 的私有数据库设计");
        verify(chatClient, never()).call(Mockito.anyString(), Mockito.anyList(), Mockito.anyList(), Mockito.anyMap());
    }

    private RagService service(VectorStore vectorStore, LocalChatClient chatClient) {
        return service(vectorStore, chatClient, new RagQualityGate(chatClient, false));
    }

    private RagService service(VectorStore vectorStore, LocalChatClient chatClient, RagQualityGate qualityGate) {
        return service(vectorStore, chatClient, qualityGate, 0.45);
    }

    private RagService service(VectorStore vectorStore, LocalChatClient chatClient, double similarityThreshold) {
        return service(vectorStore, chatClient, new RagQualityGate(chatClient, false), similarityThreshold);
    }

    private RagService service(VectorStore vectorStore, LocalChatClient chatClient, RagQualityGate qualityGate,
                               double similarityThreshold) {
        return new RagService(
                Mockito.mock(DocumentIngestionService.class),
                vectorStore,
                chatClient,
                new ConversationMemory(),
                Mockito.mock(WebSearchService.class),
                qualityGate,
                false,
                5,
                similarityThreshold,
                "distance",
                false,
                false,
                4,
                true,
                false
        );
    }
}
