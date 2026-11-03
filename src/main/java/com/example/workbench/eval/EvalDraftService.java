package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.KnowledgeChunkSampler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用真实文档分块自动生成评测题草稿。
 *
 * <p>生成结果统一进入 DRAFT 集合，必须由人在评测页审核修改后才作为基线题使用。</p>
 */
@Service
public class EvalDraftService {

    private static final Logger log = LoggerFactory.getLogger(EvalDraftService.class);
    private static final int MAX_PER_REQUEST = 10;

    private final KnowledgeChunkSampler chunkSampler;
    private final EvalCaseService evalCaseService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectMapper objectMapper;

    public EvalDraftService(KnowledgeChunkSampler chunkSampler, EvalCaseService evalCaseService,
                            ObjectProvider<ChatClient> chatClientProvider, ObjectMapper objectMapper) {
        this.chunkSampler = chunkSampler;
        this.evalCaseService = evalCaseService;
        this.chatClientProvider = chatClientProvider;
        this.objectMapper = objectMapper;
    }

    public DraftGenerationResponse generate(AppUser user, int requested) {
        ChatClient chatClient = chatClientProvider == null ? null : chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前没有可用的对话模型，无法生成评测题");
        }
        int target = Math.max(1, Math.min(MAX_PER_REQUEST, requested));
        List<KnowledgeChunkSampler.ChunkSample> samples = chunkSampler.sample(target * 3);
        if (samples.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "知识库中没有可用的文档分块，请先导入文档");
        }

        List<String> created = new ArrayList<>();
        for (KnowledgeChunkSampler.ChunkSample sample : samples) {
            if (created.size() >= target) {
                break;
            }
            Draft draft = askModel(chatClient, sample).orElse(null);
            if (draft == null) {
                continue;
            }
            EvalCaseResponse response = evalCaseService.createDraft(user, draft.question(),
                    List.of(sample.fileName()), draft.keywords());
            created.add(response.caseId());
        }
        return new DraftGenerationResponse(target, created.size(), created);
    }

    private java.util.Optional<Draft> askModel(ChatClient chatClient, KnowledgeChunkSampler.ChunkSample sample) {
        try {
            String output = chatClient.prompt()
                    .user(prompt(sample))
                    .call()
                    .content();
            Draft draft = objectMapper.readValue(cleanJson(output), Draft.class);
            return valid(draft, sample) ? java.util.Optional.of(draft) : java.util.Optional.empty();
        } catch (IOException | RuntimeException exception) {
            log.warn("Eval draft generation failed documentId={} errorType={}",
                    sample.documentId(), exception.getClass().getSimpleName());
            return java.util.Optional.empty();
        }
    }

    private boolean valid(Draft draft, KnowledgeChunkSampler.ChunkSample sample) {
        if (draft == null || draft.question() == null || draft.question().strip().length() < 6) {
            return false;
        }
        if (draft.keywords() == null || draft.keywords().isEmpty()) {
            return false;
        }
        String content = sample.content().toLowerCase(Locale.ROOT);
        return draft.keywords().stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .anyMatch(keyword -> content.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private String prompt(KnowledgeChunkSampler.ChunkSample sample) {
        return """
                你是一个 RAG 评测题编写器。下面是一份知识库文档中的真实片段。

                要求：
                1. 提出 1 个能从该片段直接找到答案的问题，不要问“这段文字讲了什么”这类泛化问题。
                2. 问题要像真实用户会问的话，不超过 40 字，不要包含“根据上文”“此片段”等提示。
                3. 给出 2-3 个关键词，必须原样出现在片段中，用于判定答案是否命中。
                4. 只输出 JSON，不要 Markdown，不要解释。

                输出格式：
                {"question": "问题", "keywords": ["关键词1", "关键词2"]}

                文件名：%s
                标题路径：%s
                片段：
                %s
                """.formatted(sample.fileName(), sample.headingPath(), sample.content());
    }

    private String cleanJson(String output) {
        if (output == null) {
            return "";
        }
        String cleaned = output.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length()).trim();
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length()).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length()).trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        return start >= 0 && end > start ? cleaned.substring(start, end + 1) : cleaned;
    }

    public record Draft(String question, List<String> keywords) {
    }

    public record DraftGenerationResponse(int requested, int created, List<String> caseIds) {
    }
}
