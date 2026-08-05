package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EvalCaseServiceTest {

    private final EvalCaseRepository repository = org.mockito.Mockito.mock(EvalCaseRepository.class);
    private final EvalCaseService service = new EvalCaseService(repository, new ObjectMapper());

    @Test
    void selectedCasesRejectsAnotherUsersId() {
        AppUser user = new AppUser("one", "One", "hash");
        EvalCaseEntity otherUsersCase = entity(new AppUser("two", "Two", "hash"));
        when(repository.existsByOwnerId(null)).thenReturn(true);
        when(repository.findAllByOwnerIdOrderByIdAsc(null)).thenReturn(List.of(otherUsersCase));

        assertThatThrownBy(() -> service.selectedCases(user, List.of(99L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Eval case not found");
    }

    @Test
    void createSerializesListsAndReturnsThem() {
        AppUser user = new AppUser("one", "One", "hash");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EvalCaseResponse response = service.create(user, request());

        assertThat(response.caseId()).isEqualTo("flow-001");
        assertThat(response.expectedKeywords()).containsExactly("keyword");
        assertThat(response.suite()).isEqualTo(EvalSuite.REGRESSION);
        assertThat(response.layer()).isEqualTo(EvalLayer.GENERATION);
    }

    @Test
    void updateUsesOwnerScopedLookup() {
        AppUser user = new AppUser("one", "One", "hash");
        EvalCaseEntity entity = entity(user);
        when(repository.findByIdAndOwnerId(1L, null)).thenReturn(Optional.of(entity));

        EvalCaseResponse response = service.update(user, 1L, request());

        assertThat(response.question()).isEqualTo("question");
    }

    @Test
    void readsLegacyRequestWithoutSuiteOrLayer() throws Exception {
        EvalCaseRequest request = new ObjectMapper().readValue("""
                {"mode":"rag","type":"fact","question":"legacy"}
                """, EvalCaseRequest.class);

        assertThat(request.normalizedSuite()).isEqualTo(EvalSuite.REGRESSION);
        assertThat(request.normalizedLayer()).isEqualTo(EvalLayer.GENERATION);
    }

    private EvalCaseEntity entity(AppUser owner) {
        EvalCaseEntity entity = new EvalCaseEntity(owner);
        entity.update(request(), "[]", "[]", "[\"keyword\"]", "[]");
        return entity;
    }

    private EvalCaseRequest request() {
        return new EvalCaseRequest("custom-1", "rag", "fact", "question", false, true, false,
                List.of(), List.of(), List.of("keyword"), List.of());
    }
}
