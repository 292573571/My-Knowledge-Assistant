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

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunner evalRunner;
    private final EvalCaseService evalCaseService;
    private final EvalRunStorage evalRunStorage;
    private final EvalCaseImportService evalCaseImportService;
    private final EvalImportStorage evalImportStorage;

    public EvalController(EvalRunner evalRunner, EvalCaseService evalCaseService, EvalRunStorage evalRunStorage, EvalCaseImportService evalCaseImportService, EvalImportStorage evalImportStorage) {
        this.evalRunner = evalRunner;
        this.evalCaseService = evalCaseService;
        this.evalRunStorage = evalRunStorage;
        this.evalCaseImportService = evalCaseImportService;
        this.evalImportStorage = evalImportStorage;
    }

    @GetMapping("/cases")
    public List<EvalCaseResponse> cases(HttpServletRequest request) {
        return evalCaseService.list(user(request));
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
    public List<EvalImportResponse> imports(HttpServletRequest request) {
        return evalImportStorage.list(user(request));
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
        return evalRunner.run(evalCaseService.selectedCases(user, run == null ? null : run.caseIds(),
                        run == null ? null : run.suite(), run == null ? null : run.layer()),
                run != null && run.enhanced(), user);
    }

    @GetMapping("/runs")
    public List<EvalRunEntity> runs(HttpServletRequest request) {
        return evalRunStorage.list(user(request));
    }

    @GetMapping("/runs/{runId}")
    public EvalSummary run(@PathVariable String runId, HttpServletRequest request) {
        return evalRunStorage.get(user(request), runId);
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
