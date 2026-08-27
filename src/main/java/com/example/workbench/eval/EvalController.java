package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.example.workbench.pagination.PageResponse;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunner evalRunner;
    private final EvalCaseService evalCaseService;
    private final EvalRunStorage evalRunStorage;
    private final EvalCaseImportService evalCaseImportService;
    private final EvalImportStorage evalImportStorage;
    private final EvalExecutionGuard executionGuard;

    public EvalController(EvalRunner evalRunner, EvalCaseService evalCaseService, EvalRunStorage evalRunStorage,
                          EvalCaseImportService evalCaseImportService, EvalImportStorage evalImportStorage,
                          EvalExecutionGuard executionGuard) {
        this.evalRunner = evalRunner;
        this.evalCaseService = evalCaseService;
        this.evalRunStorage = evalRunStorage;
        this.evalCaseImportService = evalCaseImportService;
        this.evalImportStorage = evalImportStorage;
        this.executionGuard = executionGuard;
    }

    @GetMapping("/cases")
    public Object cases(@org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
                        @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size,
                        HttpServletRequest request) {
        return page == null && size == null ? evalCaseService.list(user(request))
                : evalCaseService.page(user(request), page == null ? 0 : page, size == null ? 100 : size);
    }

    @PostMapping("/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalCaseResponse create(@Valid @RequestBody EvalCaseRequest evalCase, HttpServletRequest request) {
        return evalCaseService.create(user(request), evalCase);
    }

    @PostMapping(value = "/cases/import", consumes = "multipart/form-data")
    public EvalCaseImportResponse importCases(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        return evalCaseImportService.importCases(user(request), file);
    }

    @GetMapping("/imports")
    public Object imports(@org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
                          @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size,
                          HttpServletRequest request) {
        return page == null && size == null ? evalImportStorage.list(user(request))
                : evalImportStorage.page(user(request), page == null ? 0 : page, size == null ? 100 : size);
    }

    @GetMapping("/imports/{id}/download")
    public ResponseEntity<byte[]> downloadImport(@PathVariable Long id, HttpServletRequest request) {
        EvalImportedFile file = evalImportStorage.download(user(request), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(file.fileName(), java.nio.charset.StandardCharsets.UTF_8))
                .body(file.content());
    }

    @PutMapping("/cases/{id}")
    public EvalCaseResponse update(@PathVariable Long id, @Valid @RequestBody EvalCaseRequest evalCase,
                                   HttpServletRequest request) {
        return evalCaseService.update(user(request), id, evalCase);
    }

    @DeleteMapping("/cases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, HttpServletRequest request) {
        evalCaseService.delete(user(request), id);
    }

    @PostMapping("/run")
    public EvalSummary run(@RequestBody(required = false) EvalRunRequest run, HttpServletRequest request) throws IOException {
        AppUser user = user(request);
        List<EvalCase> cases = evalCaseService.selectedCases(user, run == null ? null : run.caseIds(),
                run == null ? null : run.suite(), run == null ? null : run.layer());
        try {
            return executionGuard.execute(user, cases.size(), () -> {
                try {
                    return evalRunner.run(cases, run != null && run.enhanced(), user);
                } catch (IOException exception) {
                    throw new EvalRunException(exception);
                }
            });
        } catch (EvalRunException exception) {
            throw exception.ioException;
        }
    }

    @GetMapping("/runs")
    public Object runs(@org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
                       @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size,
                       HttpServletRequest request) {
        return page == null && size == null ? evalRunStorage.list(user(request))
                : evalRunStorage.page(user(request), page == null ? 0 : page, size == null ? 100 : size);
    }

    @GetMapping("/runs/{runId}")
    public EvalSummary run(@PathVariable String runId, HttpServletRequest request) {
        return evalRunStorage.get(user(request), runId);
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }

    private static final class EvalRunException extends RuntimeException {
        private final IOException ioException;

        private EvalRunException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }
    }
}
