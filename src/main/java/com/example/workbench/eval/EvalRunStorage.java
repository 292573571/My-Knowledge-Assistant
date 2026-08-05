package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvalRunStorage {

    private final EvalRunRepository runRepository;
    private final EvalRunResultRepository resultRepository;
    private final ObjectMapper objectMapper;
    private final EvalDimensionSummarizer dimensionSummarizer;

    public EvalRunStorage(EvalRunRepository runRepository, EvalRunResultRepository resultRepository,
                          ObjectMapper objectMapper, EvalDimensionSummarizer dimensionSummarizer) {
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.objectMapper = objectMapper;
        this.dimensionSummarizer = dimensionSummarizer;
    }

    @Transactional
    public EvalSummary save(AppUser owner, EvalSummary summary, boolean enhanced, boolean judgeEnabled) {
        EvalRunEntity run = runRepository.save(new EvalRunEntity(owner, summary, enhanced, judgeEnabled));
        resultRepository.saveAll(summary.results().stream().map(result -> new EvalRunResultEntity(
                run, result.id(), write(result))).toList());
        return read(run);
    }

    @Transactional(readOnly = true)
    public List<EvalRunEntity> list(AppUser owner) {
        return runRepository.findAllByOwnerIdOrderByCreatedAtDesc(owner.getId());
    }

    @Transactional(readOnly = true)
    public EvalSummary get(AppUser owner, String runId) {
        EvalRunEntity run = runRepository.findByRunIdAndOwnerId(runId, owner.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Eval run not found"));
        return read(run);
    }

    private EvalSummary read(EvalRunEntity run) {
        List<EvalResult> results = resultRepository.findAllByRunIdOrderByIdAsc(run.getId()).stream()
                .map(item -> read(item.getResultJson()))
                .toList();
        return new EvalSummary(run.getRunId(), run.getTotal(), run.getPassed(), run.getFailed(), run.getPassRate(),
                run.getRetrievalHitRate(), run.getCitationCorrectnessRate(), run.getKeyPointCoverageRate(),
                run.getUnsupportedAnswerRate(), run.getModelFallbackRate(), run.getRefusalCorrectnessRate(),
                run.getRankingCaseCount(), run.getRecallAt5(), run.getPrecisionAt5(), run.getMrr(), run.getNdcgAt5(),
                run.isGateEnabled(), run.isGatePassed(), failures(run.getGateFailures()),
                dimensionSummarizer.summarize(results), results);
    }

    private String write(EvalResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize eval result", exception);
        }
    }

    private EvalResult read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, EvalResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to read eval result", exception);
        }
    }

    private List<String> failures(String value) {
        return value == null || value.isBlank() ? List.of() : value.lines().toList();
    }
}
