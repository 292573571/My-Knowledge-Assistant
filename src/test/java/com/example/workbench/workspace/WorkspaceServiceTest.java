package com.example.workbench.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AppUserRepository;
import com.example.workbench.auth.SystemRole;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.memory.ConversationMemory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class WorkspaceServiceTest {

    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository memberRepository;
    private AppUserRepository userRepository;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);
        memberRepository = Mockito.mock(WorkspaceMemberRepository.class);
        userRepository = Mockito.mock(AppUserRepository.class);
        service = new WorkspaceService(workspaceRepository, memberRepository, userRepository);
        when(workspaceRepository.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(memberRepository.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsAPersonalWorkspaceLazilyOnlyWhenMissing() {
        AppUser user = user(1L, "alice", "usr_alice", "Alice");
        when(workspaceRepository.findByOwnerIdAndType(1L, WorkspaceType.PERSONAL)).thenReturn(Optional.empty());
        when(memberRepository.findAllByUserIdOrderByWorkspaceCreatedAtAsc(1L)).thenAnswer(invocation -> List.of(
                new WorkspaceMember(new Workspace("personal-1", "Alice 的个人空间", WorkspaceType.PERSONAL, user), user, WorkspaceRole.OWNER)
        ));

        List<WorkspaceResponse> result = service.list(user);

        assertThat(result).singleElement().satisfies(workspace -> {
            assertThat(workspace.id()).isEqualTo("personal-1");
            assertThat(workspace.type()).isEqualTo(WorkspaceType.PERSONAL);
            assertThat(workspace.role()).isEqualTo(WorkspaceRole.OWNER);
        });
        verify(workspaceRepository).save(Mockito.argThat(workspace -> workspace.getType() == WorkspaceType.PERSONAL));
        verify(memberRepository).save(Mockito.argThat(member -> member.getRole() == WorkspaceRole.OWNER));
    }

    @Test
    void doesNotCreateAnotherPersonalWorkspaceWhenOneExists() {
        AppUser user = user(1L, "alice", "usr_alice", "Alice");
        Workspace existing = new Workspace("personal-1", "Alice 的个人空间", WorkspaceType.PERSONAL, user);
        when(workspaceRepository.findByOwnerIdAndType(1L, WorkspaceType.PERSONAL)).thenReturn(Optional.of(existing));
        when(memberRepository.findAllByUserIdOrderByWorkspaceCreatedAtAsc(1L)).thenReturn(List.of(new WorkspaceMember(existing, user, WorkspaceRole.OWNER)));

        service.list(user);

        verify(workspaceRepository, never()).save(Mockito.any());
    }

    @Test
    void administratorsCanDiscoverAndMaintainPublicKnowledgeSourcesWithoutMembership() {
        AppUser admin = user(1L, "ops", "usr_ops", "Ops");
        when(admin.getSystemRole()).thenReturn(SystemRole.ADMIN);
        Workspace personal = new Workspace("personal-1", "Ops 的个人空间", WorkspaceType.PERSONAL, admin);
        Workspace publicWorkspace = new Workspace("public-1", "平台公共知识", WorkspaceType.PUBLIC, admin);
        when(workspaceRepository.findByOwnerIdAndType(1L, WorkspaceType.PERSONAL)).thenReturn(Optional.of(personal));
        when(memberRepository.findAllByUserIdOrderByWorkspaceCreatedAtAsc(1L))
                .thenReturn(List.of(new WorkspaceMember(personal, admin, WorkspaceRole.OWNER)));
        when(workspaceRepository.findAllByTypeOrderByCreatedAtAsc(WorkspaceType.PUBLIC)).thenReturn(List.of(publicWorkspace));
        when(memberRepository.findByWorkspaceIdAndUserId("public-1", 1L)).thenReturn(Optional.empty());
        when(workspaceRepository.findById("public-1")).thenReturn(Optional.of(publicWorkspace));

        assertThat(service.list(admin)).extracting(WorkspaceResponse::id)
                .containsExactly("personal-1", "public-1");
        assertThat(service.access(admin, "public-1"))
                .satisfies(access -> {
                    assertThat(access.type()).isEqualTo(WorkspaceType.PUBLIC);
                    assertThat(access.role()).isEqualTo(WorkspaceRole.EDITOR);
                    assertThat(access.canWrite()).isTrue();
                });
    }

    @Test
    void regularUsersDoNotDiscoverPublicKnowledgeSources() {
        AppUser user = user(2L, "bob", "usr_bob", "Bob");
        when(user.getSystemRole()).thenReturn(SystemRole.USER);
        Workspace personal = new Workspace("personal-2", "Bob 的个人空间", WorkspaceType.PERSONAL, user);
        when(workspaceRepository.findByOwnerIdAndType(2L, WorkspaceType.PERSONAL)).thenReturn(Optional.of(personal));
        when(memberRepository.findAllByUserIdOrderByWorkspaceCreatedAtAsc(2L))
                .thenReturn(List.of(new WorkspaceMember(personal, user, WorkspaceRole.OWNER)));

        assertThat(service.list(user)).extracting(WorkspaceResponse::id).containsExactly("personal-2");
        verify(workspaceRepository, never()).findAllByTypeOrderByCreatedAtAsc(WorkspaceType.PUBLIC);
    }

    @Test
    void ownerCanAddAnEditorToTeamWorkspace() {
        AppUser owner = user(1L, "alice", "usr_alice", "Alice");
        AppUser member = user(2L, "bob", "usr_bob", "Bob");
        Workspace workspace = new Workspace("team-1", "研发团队", WorkspaceType.TEAM, owner);
        when(memberRepository.findByWorkspaceIdAndUserId("team-1", 1L))
                .thenReturn(Optional.of(new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER)));
        when(userRepository.findByAccount("bob")).thenReturn(Optional.of(member));
        when(memberRepository.findByWorkspaceIdAndUserId("team-1", 2L)).thenReturn(Optional.empty());

        WorkspaceMemberResponse response = service.addMember(owner, "team-1", new AddWorkspaceMemberRequest("BOB", WorkspaceRole.EDITOR));

        assertThat(response.publicId()).isEqualTo("usr_bob");
        assertThat(response.role()).isEqualTo(WorkspaceRole.EDITOR);
    }

    @Test
    void nonOwnerCannotManageMembers() {
        AppUser editor = user(2L, "bob", "usr_bob", "Bob");
        AppUser candidate = user(3L, "carol", "usr_carol", "Carol");
        Workspace workspace = new Workspace("team-1", "研发团队", WorkspaceType.TEAM, user(1L, "alice", "usr_alice", "Alice"));
        when(memberRepository.findByWorkspaceIdAndUserId("team-1", 2L))
                .thenReturn(Optional.of(new WorkspaceMember(workspace, editor, WorkspaceRole.EDITOR)));

        assertThatThrownBy(() -> service.addMember(editor, "team-1", new AddWorkspaceMemberRequest(candidate.getAccount(), WorkspaceRole.VIEWER)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        verify(userRepository, never()).findByAccount(Mockito.anyString());
    }

    @Test
    void nonMemberCannotResolveAnotherTeamsAccessContext() {
        AppUser outsider = user(3L, "carol", "usr_carol", "Carol");
        when(workspaceRepository.findByOwnerIdAndType(3L, WorkspaceType.PERSONAL))
                .thenReturn(Optional.of(new Workspace("personal-3", "Carol 的个人空间", WorkspaceType.PERSONAL, outsider)));
        when(memberRepository.findByWorkspaceIdAndUserId("team-1", 3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.access(outsider, "team-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void onlyOwnerCanReadWorkspaceAuditHistory() {
        AppUser viewer = user(2L, "bob", "usr_bob", "Bob");
        Workspace workspace = new Workspace("team-1", "研发团队", WorkspaceType.TEAM,
                user(1L, "alice", "usr_alice", "Alice"));
        when(memberRepository.findByWorkspaceIdAndUserId("team-1", 2L))
                .thenReturn(Optional.of(new WorkspaceMember(workspace, viewer, WorkspaceRole.VIEWER)));

        assertThatThrownBy(() -> service.ownerAccess(viewer, "team-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void ownerCannotBeRemovedOrDowngraded() {
        AppUser owner = user(1L, "alice", "usr_alice", "Alice");
        Workspace workspace = new Workspace("team-1", "研发团队", WorkspaceType.TEAM, owner);
        WorkspaceMember ownerMembership = new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER);
        when(memberRepository.findByWorkspaceIdAndUserId("team-1", 1L)).thenReturn(Optional.of(ownerMembership));
        when(userRepository.findByPublicId("usr_alice")).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.changeRole(owner, "team-1", "usr_alice", new UpdateWorkspaceMemberRoleRequest(WorkspaceRole.VIEWER)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能修改空间所有者角色");
        assertThatThrownBy(() -> service.removeMember(owner, "team-1", "usr_alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能移除空间所有者");
        verify(memberRepository, never()).delete(Mockito.any());
    }

    @Test
    void roleMatrixAllowsOnlyOwnerAndEditorToWrite() {
        assertThat(new WorkspaceAccessContext("owner", "team-1", WorkspaceRole.OWNER, WorkspaceType.TEAM).canWrite())
                .isTrue();
        assertThat(new WorkspaceAccessContext("editor", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM).canWrite())
                .isTrue();
        assertThat(new WorkspaceAccessContext("viewer", "team-1", WorkspaceRole.VIEWER, WorkspaceType.TEAM).canWrite())
                .isFalse();
    }

    @Test
    void removingMemberCancelsRunningWorkspaceConversationsAndClearsMemory() {
        AppUser owner = user(1L, "alice", "usr_alice", "Alice");
        AppUser member = user(2L, "bob", "usr_bob", "Bob");
        Workspace workspace = new Workspace("team-1", "研发团队", WorkspaceType.TEAM, owner);
        WorkspaceMember ownerMembership = new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER);
        WorkspaceMember memberMembership = new WorkspaceMember(workspace, member, WorkspaceRole.VIEWER);
        when(memberRepository.findByWorkspaceIdAndUserId("team-1", 1L)).thenReturn(Optional.of(ownerMembership));
        when(userRepository.findByPublicId("usr_bob")).thenReturn(Optional.of(member));
        when(memberRepository.findByWorkspaceIdAndUserId("team-1", 2L)).thenReturn(Optional.of(memberMembership));
        ConversationExecutionRegistry executions = new ConversationExecutionRegistry();
        ConversationMemory memory = new ConversationMemory();
        service.setRevocationResources(executions, memory);
        String scope = UserConversationScope.id(member, "team-1:conversation-a");
        ConversationExecutionRegistry.Execution execution = executions.begin(scope);
        memory.addUserMessage(scope, "敏感问题");

        service.removeMember(owner, "team-1", "usr_bob");

        assertThat(execution.isCancelled()).isTrue();
        assertThat(memory.get(scope)).isEmpty();
        verify(memberRepository).delete(memberMembership);
    }

    @Test
    void regularUserCanCreateIndependentTeam() {
        AppUser owner = user(1L, "alice", "usr_alice", "Alice");

        WorkspaceResponse response = service.createTeam(owner,
                new CreateWorkspaceRequest("独立团队", null));

        assertThat(response.type()).isEqualTo(WorkspaceType.TEAM);
        assertThat(response.parentId()).isNull();
        verify(memberRepository).save(Mockito.argThat(member -> member.getRole() == WorkspaceRole.OWNER));
    }

    @Test
    void organizationOwnerCanCreateTeamUnderRootOrganization() {
        AppUser owner = user(1L, "alice", "usr_alice", "Alice");
        Workspace org = new Workspace("org-1", "研发组织", WorkspaceType.ORG, owner);
        when(workspaceRepository.findById("org-1")).thenReturn(Optional.of(org));
        when(memberRepository.findByWorkspaceIdAndUserId("org-1", 1L))
                .thenReturn(Optional.of(new WorkspaceMember(org, owner, WorkspaceRole.OWNER)));

        WorkspaceResponse response = service.createTeamUnder(owner, " org-1 ",
                new CreateWorkspaceRequest("组织团队", "org-1"));

        assertThat(response.type()).isEqualTo(WorkspaceType.TEAM);
        assertThat(response.parentId()).isEqualTo("org-1");
    }

    @Test
    void systemAdminCanCreateTeamUnderRootOrganizationWithoutMembership() {
        AppUser admin = user(1L, "ops", "usr_ops", "Ops");
        when(admin.getSystemRole()).thenReturn(SystemRole.ADMIN);
        Workspace org = new Workspace("org-1", "研发组织", WorkspaceType.ORG, user(2L, "alice", "usr_alice", "Alice"));
        when(workspaceRepository.findById("org-1")).thenReturn(Optional.of(org));

        WorkspaceResponse response = service.createTeamUnder(admin, "org-1",
                new CreateWorkspaceRequest("管理员团队", "org-1"));

        assertThat(response.parentId()).isEqualTo("org-1");
        verify(memberRepository, never()).findByWorkspaceIdAndUserId("org-1", 1L);
    }

    @Test
    void regularMemberCannotCreateTeamUnderOrganization() {
        AppUser member = user(1L, "bob", "usr_bob", "Bob");
        Workspace org = new Workspace("org-1", "研发组织", WorkspaceType.ORG, user(2L, "alice", "usr_alice", "Alice"));
        when(workspaceRepository.findById("org-1")).thenReturn(Optional.of(org));
        when(memberRepository.findByWorkspaceIdAndUserId("org-1", 1L))
                .thenReturn(Optional.of(new WorkspaceMember(org, member, WorkspaceRole.EDITOR)));

        assertThatThrownBy(() -> service.createTeamUnder(member, "org-1",
                new CreateWorkspaceRequest("越权团队", "org-1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void teamCannotBeCreatedUnderTeamOrWithInvalidName() {
        AppUser owner = user(1L, "alice", "usr_alice", "Alice");
        Workspace parentTeam = new Workspace("team-1", "已有团队", WorkspaceType.TEAM, owner);
        when(workspaceRepository.findById("team-1")).thenReturn(Optional.of(parentTeam));

        assertThatThrownBy(() -> service.createTeamUnder(owner, "team-1",
                new CreateWorkspaceRequest("子团队", "team-1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("根组织");
        assertThatThrownBy(() -> service.createTeam(owner,
                new CreateWorkspaceRequest("   ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("团队名称");
    }

    private AppUser user(Long id, String account, String publicId, String name) {
        AppUser user = Mockito.mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getAccount()).thenReturn(account);
        when(user.getPublicId()).thenReturn(publicId);
        when(user.getUserName()).thenReturn(name);
        return user;
    }
}
