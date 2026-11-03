package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EvalCaseServiceMoveSuiteTest {

    @Mock
    private EvalCaseRepository repository;

    private EvalCaseService service;
    private AppUser admin;

    @BeforeEach
    void setUp() {
        service = new EvalCaseService(repository, new ObjectMapper());
        admin = new AppUser("admin", "管理员", "hash");
        ReflectionTestUtils.setField(admin, "id", 1L);
    }

    @Test
    void movesOnlySourceSuiteCases() {
        EvalCaseEntity draftA = caseOf("draft-001", EvalSuite.DRAFT);
        EvalCaseEntity draftB = caseOf("draft-002", EvalSuite.DRAFT);
        EvalCaseEntity regression = caseOf("case-001", EvalSuite.REGRESSION);
        when(repository.findAllByOwnerIdOrderByIdAsc(1L))
                .thenReturn(List.of(draftA, draftB, regression));

        int moved = service.moveSuite(admin, EvalSuite.DRAFT, EvalSuite.REGRESSION);

        assertThat(moved).isEqualTo(2);
        assertThat(draftA.getSuite()).isEqualTo(EvalSuite.REGRESSION);
        assertThat(draftB.getSuite()).isEqualTo(EvalSuite.REGRESSION);
        assertThat(regression.getSuite()).isEqualTo(EvalSuite.REGRESSION);
    }

    @Test
    void rejectsSameSuite() {
        assertThatThrownBy(() -> service.moveSuite(admin, EvalSuite.DRAFT, EvalSuite.DRAFT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能相同");
    }

    @Test
    void returnsZeroWhenNothingMatches() {
        when(repository.findAllByOwnerIdOrderByIdAsc(1L))
                .thenReturn(List.of(caseOf("case-001", EvalSuite.REGRESSION)));

        assertThat(service.moveSuite(admin, EvalSuite.DRAFT, EvalSuite.REGRESSION)).isZero();
    }

    private EvalCaseEntity caseOf(String caseId, EvalSuite suite) {
        EvalCaseEntity entity = new EvalCaseEntity(admin);
        EvalCaseRequest request = new EvalCaseRequest(caseId, "rag", "fact", "问题", false, true, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                suite.name(), "RETRIEVAL", List.of(), null, null, List.of());
        entity.update(request, "[]", "[]", "[]", "[]", "[]", "[]", "[]", "[]", "[]");
        return entity;
    }
}
