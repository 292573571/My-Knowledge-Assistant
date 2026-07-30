package com.example.workbench.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AuditServiceTest {

    @Test
    void recordsOnlyStructuredMetadata() {
        AuditEventRepository repository = Mockito.mock(AuditEventRepository.class);
        AuditService service = new AuditService(repository);
        AppUser actor = Mockito.mock(AppUser.class);
        when(actor.getPublicId()).thenReturn("usr_actor");

        service.record(actor, "team-1", AuditAction.DOCUMENT_UPLOAD, "DOCUMENT", "doc-1",
                AuditOutcome.SUCCESS, "NONE", "req-1");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getActorPublicId()).isEqualTo("usr_actor");
        assertThat(event.getWorkspaceId()).isEqualTo("team-1");
        assertThat(event.getResourceId()).isEqualTo("doc-1");
        assertThat(event.getReasonCode()).isEqualTo("NONE");
        assertThat(event.getRequestId()).isEqualTo("req-1");
    }

    @Test
    void convertsAuthorizationFailuresToStableCodesWithoutExceptionMessages() {
        AuditService service = new AuditService(Mockito.mock(AuditEventRepository.class));
        RuntimeException exception = new ResponseStatusException(HttpStatus.FORBIDDEN,
                "sensitive account or document detail must not be stored");

        assertThat(service.outcome(exception)).isEqualTo(AuditOutcome.DENIED);
        assertThat(service.reasonCode(exception)).isEqualTo("HTTP_403")
                .doesNotContain("sensitive")
                .doesNotContain("document detail");
    }

    @Test
    void auditResponsesExposePublicIdentityInsteadOfAccountOrDatabaseId() {
        AuditEventRepository repository = Mockito.mock(AuditEventRepository.class);
        AuditService service = new AuditService(repository);
        AuditEvent event = new AuditEvent("usr_public", "team-1", AuditAction.WORKSPACE_MEMBER_ADD,
                "USER", "usr_member", AuditOutcome.SUCCESS, "NONE", "req-2");
        when(repository.findTop200ByWorkspaceIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of(event));

        assertThat(service.list("team-1")).singleElement().satisfies(response -> {
            assertThat(response.actorPublicId()).isEqualTo("usr_public");
            assertThat(response.resourceId()).isEqualTo("usr_member");
            assertThat(response.requestId()).isEqualTo("req-2");
        });
    }

    @Test
    void truncatesUntrustedIdentifiersToDatabaseColumnLimits() {
        AuditEvent event = new AuditEvent("u".repeat(100), "w".repeat(100), AuditAction.DOCUMENT_DELETE,
                "T".repeat(100), "r".repeat(200), AuditOutcome.DENIED, "C".repeat(100), "q".repeat(100));

        assertThat(event.getActorPublicId()).hasSize(64);
        assertThat(event.getWorkspaceId()).hasSize(36);
        assertThat(event.getResourceType()).hasSize(32);
        assertThat(event.getResourceId()).hasSize(128);
        assertThat(event.getReasonCode()).hasSize(80);
        assertThat(event.getRequestId()).hasSize(64);
    }
}
