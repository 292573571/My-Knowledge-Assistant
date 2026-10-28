package com.example.workbench.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class UsageControllerTest {

    private final UsageQueryService usageQueryService = mock(UsageQueryService.class);
    private final AdminAuthorizationService adminAuthorizationService = mock(AdminAuthorizationService.class);
    private final UsageController controller = new UsageController(usageQueryService, adminAuthorizationService);

    private MockHttpServletRequest requestFor(AppUser user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user);
        return request;
    }

    @Test
    void scopeAllRequiresSuperAdminAndReturnsSystemWideStats() {
        AppUser admin = new AppUser("admin", "管理员", "hash");
        when(usageQueryService.modelStatsAll(30)).thenReturn(List.of());

        assertThat(controller.modelStats(30, "all", requestFor(admin))).isEmpty();
        verify(adminAuthorizationService).requireSuperAdmin(admin);
        verify(usageQueryService).modelStatsAll(30);
        verify(usageQueryService, never()).modelStats(org.mockito.ArgumentMatchers.anyLong(), eq(30));
    }

    @Test
    void scopeAllRejectedForNonSuperAdmin() {
        AppUser alice = new AppUser("alice", "爱丽丝", "hash");
        org.springframework.test.util.ReflectionTestUtils.setField(alice, "id", 7L);
        org.mockito.Mockito.doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(adminAuthorizationService).requireSuperAdmin(alice);

        assertThatThrownBy(() -> controller.modelStats(30, "all", requestFor(alice)))
                .isInstanceOf(ResponseStatusException.class);
        verify(usageQueryService, never()).modelStatsAll(30);
    }

    @Test
    void defaultScopeReturnsOwnStats() {
        AppUser alice = new AppUser("alice", "爱丽丝", "hash");
        org.springframework.test.util.ReflectionTestUtils.setField(alice, "id", 7L);
        when(usageQueryService.modelStats(7L, 30)).thenReturn(List.of());

        assertThat(controller.modelStats(30, "self", requestFor(alice))).isEmpty();
        verify(usageQueryService).modelStats(7L, 30);
        verify(adminAuthorizationService, never()).requireSuperAdmin(alice);
    }
}
