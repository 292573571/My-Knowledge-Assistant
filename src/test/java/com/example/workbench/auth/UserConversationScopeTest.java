package com.example.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserConversationScopeTest {

    @Test
    void scopesTheSameClientConversationIdToDifferentUsers() {
        AppUser firstUser = Mockito.mock(AppUser.class);
        AppUser secondUser = Mockito.mock(AppUser.class);
        when(firstUser.getId()).thenReturn(1L);
        when(secondUser.getId()).thenReturn(2L);

        String firstScope = UserConversationScope.id(firstUser, "shared-conversation");
        String secondScope = UserConversationScope.id(secondUser, "shared-conversation");

        assertThat(firstScope).isEqualTo("user-1:shared-conversation");
        assertThat(secondScope).isEqualTo("user-2:shared-conversation");
        assertThat(firstScope).isNotEqualTo(secondScope);
    }
}
