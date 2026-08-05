package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.example.workbench.memory.ChatMessage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvalCaseService {

    private static final Path QUESTIONS_PATH = Path.of("eval", "questions.jsonl");
    private static final int SEED_CASE_LIMIT = 32;
    private static final Pattern FLOW_CASE_ID = Pattern.compile("flow-(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() { };
    private static final TypeReference<List<ChatMessage>> MESSAGE_LIST = new TypeReference<>() { };

    private final EvalCaseRepository repository;
    private final ObjectMapper objectMapper;

    public EvalCaseService(EvalCaseRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<EvalCaseResponse> list(AppUser user) {
        seedIfNeeded(user);
        return repository.findAllByOwnerIdOrderByIdAsc(user.getId()).stream().map(this::response).toList();
    }

    @Transactional
    public EvalCaseResponse create(AppUser user, EvalCaseRequest request) {
        seedIfNeeded(user);
        return response(repository.save(entity(user, withCaseId(request, nextCaseId(user)))));
    }

    @Transactional
    public EvalCaseResponse update(AppUser user, Long id, EvalCaseRequest request) {
        EvalCaseEntity entity = owned(user, id);
        update(entity, withCaseId(request, entity.getCaseId()));
        return response(entity);
    }

    @Transactional
    public void delete(AppUser user, Long id) {
        repository.delete(owned(user, id));
    }

    @Transactional
    public List<EvalCase> selectedCases(AppUser user, List<Long> ids) {
        return selectedCases(user, ids, null, null);
    }

    @Transactional
    public List<EvalCase> selectedCases(AppUser user, List<Long> ids, String suite, String layer) {
        seedIfNeeded(user);
        List<EvalCaseEntity> cases = repository.findAllByOwnerIdOrderByIdAsc(user.getId());
        if (ids != null && !ids.isEmpty()) {
            long selectedCount = cases.stream()
                    .map(EvalCaseEntity::getId)
                    .filter(id -> id != null && ids.stream().anyMatch(id::equals))
                    .count();
            if (selectedCount != ids.stream().filter(id -> id != null).distinct().count()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Eval case not found");
            }
            cases = cases.stream()
                    .filter(item -> item.getId() != null && ids.stream().anyMatch(item.getId()::equals))
                    .toList();
        }
        EvalSuite requestedSuite = suite == null || suite.isBlank() ? null : EvalSuite.from(suite);
        EvalLayer requestedLayer = layer == null || layer.isBlank() ? null : EvalLayer.from(layer);
        if (requestedSuite != null || requestedLayer != null) {
            cases = cases.stream()
                    .filter(item -> requestedSuite == null || item.getSuite() == requestedSuite)
                    .filter(item -> requestedLayer == null || item.getLayer() == requestedLayer)
                    .toList();
        }
        return cases.stream().map(this::evalCase).toList();
    }

    private void seedIfNeeded(AppUser user) {
        if (repository.existsByOwnerId(user.getId())) {
            return;
        }
        try {
            Files.readAllLines(QUESTIONS_PATH, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .limit(SEED_CASE_LIMIT)
                    .map(line -> readTemplate(line, user))
                    .forEach(repository::save);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to seed eval cases", exception);
        }
    }

    private EvalCaseEntity readTemplate(String line, AppUser user) {
        try {
            EvalCase template = objectMapper.readValue(line, EvalCase.class);
            EvalCaseEntity entity = new EvalCaseEntity(user);
            entity.update(new EvalCaseRequest(template.id(), template.mode(), template.type(), template.question(),
                    template.expectNoAnswer(), template.requireLocalEvidence(), template.allowModelFallback(),
                    template.expectedSources(), template.expectedHeadingPaths(), template.expectedKeywords(), template.forbiddenKeywords(),
                    template.expectedPageNumbers(), template.expectedRetrievalKeywords(), template.forbiddenRetrievalKeywords(),
                    template.suite().name(), template.layer().name(), template.history(), template.expectedRelation(),
                    template.expectedStandaloneQuestion(), template.expectedRetrievalQueries()),
                    json(template.expectedSources()), json(template.expectedHeadingPaths()),
                    json(template.expectedKeywords()), json(template.forbiddenKeywords()), json(template.expectedPageNumbers()),
                    json(template.expectedRetrievalKeywords()), json(template.forbiddenRetrievalKeywords()),
                    json(template.history()), json(template.expectedRetrievalQueries()));
            return entity;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read eval case template", exception);
        }
    }

    private EvalCaseEntity entity(AppUser user, EvalCaseRequest request) {
        EvalCaseEntity entity = new EvalCaseEntity(user);
        update(entity, request);
        return entity;
    }

    private String nextCaseId(AppUser user) {
        int next = repository.findAllByOwnerIdOrderByIdAsc(user.getId()).stream()
                .map(EvalCaseEntity::getCaseId)
                .map(FLOW_CASE_ID::matcher)
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElse(0) + 1;
        return "flow-%03d".formatted(next);
    }

    private EvalCaseRequest withCaseId(EvalCaseRequest request, String caseId) {
        return new EvalCaseRequest(caseId, request.mode(), request.type(), request.question(),
                request.expectNoAnswer(), request.requireLocalEvidence(), request.allowModelFallback(),
                request.expectedSources(), request.expectedHeadingPaths(), request.expectedKeywords(), request.forbiddenKeywords(),
                request.expectedPageNumbers(), request.expectedRetrievalKeywords(), request.forbiddenRetrievalKeywords(),
                request.normalizedSuite().name(), request.normalizedLayer().name(), request.history(), request.expectedRelation(),
                request.expectedStandaloneQuestion(), request.expectedRetrievalQueries());
    }

    private void update(EvalCaseEntity entity, EvalCaseRequest request) {
        entity.update(request, json(request.expectedSources()), json(request.expectedHeadingPaths()),
                json(request.expectedKeywords()), json(request.forbiddenKeywords()), json(request.expectedPageNumbers()),
                json(request.expectedRetrievalKeywords()), json(request.forbiddenRetrievalKeywords()),
                json(request.history()), json(request.expectedRetrievalQueries()));
    }

    private EvalCaseEntity owned(AppUser user, Long id) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Eval case not found"));
    }

    private EvalCaseResponse response(EvalCaseEntity entity) {
        return new EvalCaseResponse(entity.getId(), entity.getCaseId(), entity.getMode(), entity.getType(), entity.getQuestion(),
                entity.isExpectNoAnswer(), entity.isRequireLocalEvidence(), entity.isAllowModelFallback(),
                list(entity.getExpectedSources()), list(entity.getExpectedHeadingPaths()),
                list(entity.getExpectedKeywords()), list(entity.getForbiddenKeywords()),
                integerList(entity.getExpectedPageNumbers()), list(entity.getExpectedRetrievalKeywords()),
                list(entity.getForbiddenRetrievalKeywords()), entity.getSuite(), entity.getLayer(),
                messages(entity.getHistory()), entity.getExpectedRelation(), entity.getExpectedStandaloneQuestion(),
                list(entity.getExpectedRetrievalQueries()));
    }

    private EvalCase evalCase(EvalCaseEntity entity) {
        return new EvalCase(entity.getCaseId(), entity.getMode(), entity.getType(), entity.getQuestion(),
                entity.isExpectNoAnswer(), entity.isRequireLocalEvidence(), entity.isAllowModelFallback(),
                list(entity.getExpectedSources()), list(entity.getExpectedHeadingPaths()),
                list(entity.getExpectedKeywords()), list(entity.getForbiddenKeywords()),
                integerList(entity.getExpectedPageNumbers()), list(entity.getExpectedRetrievalKeywords()),
                list(entity.getForbiddenRetrievalKeywords()), entity.getSuite(), entity.getLayer(),
                messages(entity.getHistory()), entity.getExpectedRelation(), entity.getExpectedStandaloneQuestion(),
                list(entity.getExpectedRetrievalQueries()));
    }

    private String json(List<?> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid eval case list", exception);
        }
    }

    private List<String> list(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid stored eval case list", exception);
        }
    }

    private List<Integer> integerList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, INTEGER_LIST);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid stored eval case page list", exception);
        }
    }

    private List<ChatMessage> messages(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, MESSAGE_LIST);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid stored eval case history", exception);
        }
    }
}
