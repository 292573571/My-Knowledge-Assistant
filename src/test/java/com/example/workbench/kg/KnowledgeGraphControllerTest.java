package com.example.workbench.kg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.workspace.WorkspaceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class KnowledgeGraphControllerTest {

    @Mock
    private KgEntityRepository entityRepository;

    @Mock
    private KgRelationRepository relationRepository;

    @Mock
    private WorkspaceService workspaceService;

    @InjectMocks
    private KnowledgeGraphController controller;

    private MockHttpServletRequest request;
    private AppUser user;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        user = org.mockito.Mockito.mock(AppUser.class);
        request.setAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user);
    }

    @Test
    void graphReturnsNodesAndEdgesAndChecksAccess() {
        KgEntity page = new KgEntity();
        page.setId("wiki:p1");
        page.setEntityType(KgTypes.ENTITY_WIKI_PAGE);
        page.setName("Alpha");
        page.setSourceId("p1");

        KgRelation rel = new KgRelation();
        rel.setSourceEntityId("wiki:p1");
        rel.setTargetEntityId("wiki:p2");
        rel.setRelationType(KgTypes.REL_WIKI_LINK);
        rel.setWeight(1);

        when(entityRepository.findByWorkspaceIdOrderByEntityTypeAscNameAsc("ws")).thenReturn(List.of(page));
        when(relationRepository.findByWorkspaceIdOrderByRelationTypeAsc("ws")).thenReturn(List.of(rel));

        KgGraphDto dto = controller.graph("ws", request);

        verify(workspaceService).access(user, "ws");
        assertNotNull(dto);
        assertEquals(1, dto.getNodes().size());
        assertEquals("wiki:p1", dto.getNodes().get(0).getId());
        assertEquals(1, dto.getEdges().size());
        assertEquals(KgTypes.REL_WIKI_LINK, dto.getEdges().get(0).getType());
    }
}
