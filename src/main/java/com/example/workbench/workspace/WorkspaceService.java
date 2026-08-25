package com.example.workbench.workspace;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AppUserRepository;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.auth.SystemRole;
import com.example.workbench.conversation.ConversationExecutionRegistry;
import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.config.LoggingContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final AppUserRepository userRepository;
    private ConversationExecutionRegistry executionRegistry;
    private ConversationMemory conversationMemory;

    /**
     * 注入成员撤权时使用的运行会话注册表和短期记忆。
     *
     * @param executionRegistry 运行会话注册表
     * @param conversationMemory 短期对话记忆
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRevocationResources(ConversationExecutionRegistry executionRegistry,
                                       ConversationMemory conversationMemory) {
        this.executionRegistry = executionRegistry;
        this.conversationMemory = conversationMemory;
    }

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository memberRepository,
            AppUserRepository userRepository
    ) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public List<WorkspaceResponse> list(AppUser user) {
        ensurePersonalWorkspace(user);
        Set<String> seen = new HashSet<>();
        List<WorkspaceResponse> result = new ArrayList<>();
        for (WorkspaceMember membership : memberRepository.findAllByUserIdOrderByWorkspaceCreatedAtAsc(user.getId())) {
            Workspace workspace = membership.getWorkspace();
            addWithAncestors(result, seen, workspace, membership.getRole());
        }
        if (isAdmin(user)) {
            workspaceRepository.findAllByTypeOrderByCreatedAtAsc(WorkspaceType.PUBLIC).stream()
                    .filter(workspace -> !seen.contains(workspace.getId()))
                    .map(workspace -> new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getType(),
                            WorkspaceRole.EDITOR, workspace.getCreatedAt(), workspace.getParent() == null ? null : workspace.getParent().getId()))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    /**
     * 将空间本身及其所有祖先空间（组织层级）加入结果，使团队成员也能在列表中看到所属组织。
     */
    private void addWithAncestors(List<WorkspaceResponse> result, Set<String> seen, Workspace workspace, WorkspaceRole role) {
        Workspace current = workspace;
        while (current != null && seen.add(current.getId())) {
            result.add(response(current, role));
            current = current.getParent();
        }
    }

    @Transactional
    public WorkspaceAccessContext access(AppUser user, String requestedWorkspaceId) {
        ensurePersonalWorkspace(user);
        String workspaceId = requestedWorkspaceId == null || requestedWorkspaceId.isBlank()
                ? "personal-" + user.getId()
                : requestedWorkspaceId.strip();
        LoggingContext.put(LoggingContext.WORKSPACE_ID, workspaceId);
        WorkspaceMember membership = memberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId()).orElse(null);
        if (membership == null) {
            Workspace workspace = workspaceRepository.findById(workspaceId)
                    .filter(item -> item.getType() == WorkspaceType.PUBLIC && isAdmin(user))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识空间不存在"));
            return new WorkspaceAccessContext(UserConversationScope.ownerId(user), workspaceId, WorkspaceRole.EDITOR,
                    workspace.getType());
        }
        return new WorkspaceAccessContext(UserConversationScope.ownerId(user), workspaceId, membership.getRole(),
                membership.getWorkspace().getType());
    }

    @Transactional
    public WorkspaceAccessContext ownerAccess(AppUser user, String workspaceId) {
        ensurePersonalWorkspace(user);
        WorkspaceMember membership = requireOwner(user, workspaceId);
        return new WorkspaceAccessContext(UserConversationScope.ownerId(user), workspaceId, membership.getRole(),
                membership.getWorkspace().getType());
    }

    @Transactional
    public WorkspaceResponse createOrg(AppUser owner, CreateWorkspaceRequest request) {
        if (!isSuperAdmin(owner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有超级管理员可以创建根组织");
        }
        if (workspaceRepository.findByNameAndType(request.name().strip(), WorkspaceType.ORG).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同名根组织已存在");
        }
        return create(owner, request.name(), WorkspaceType.ORG);
    }

    @Transactional
    public WorkspaceResponse createTeam(AppUser owner, CreateWorkspaceRequest request) {
        return create(owner, request.name(), WorkspaceType.TEAM);
    }

    @Transactional
    public WorkspaceResponse createTeamUnder(AppUser actor, String parentId, CreateWorkspaceRequest request) {
        Workspace parent = workspaceRepository.findById(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "上级组织不存在"));
        if (parent.getType() != WorkspaceType.ORG) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "团队只能挂在根组织之下");
        }
        if (!isSuperAdmin(actor)) {
            WorkspaceMember membership = memberRepository.findByWorkspaceIdAndUserId(parentId, actor.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "只有根组织所有者或超级管理员可以新建团队"));
            if (membership.getRole() != WorkspaceRole.OWNER) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有根组织所有者或超级管理员可以新建团队");
            }
        }
        Workspace workspace = workspaceRepository.save(new Workspace(request.name().strip(), WorkspaceType.TEAM, actor));
        workspace.setParent(parent);
        WorkspaceMember membership = memberRepository.save(new WorkspaceMember(workspace, actor, WorkspaceRole.OWNER));
        return response(membership);
    }

    @Transactional
    public WorkspaceResponse createPublic(AppUser owner, CreateWorkspaceRequest request) {
        return create(owner, request.name(), WorkspaceType.PUBLIC);
    }

    /**
     * 计算当前空间下用户「有效可读」的空间集合，用于 RAG 检索时的可见性过滤：
     * <ul>
     *   <li>组织（根）空间：自身 + 其下所有团队（组织成员可 oversight 全部团队文档）；</li>
     *   <li>团队/个人空间：自身 + 沿父链向上的所有祖先（组织共享文档对团队可见）。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Set<String> effectiveReadableWorkspaceIds(AppUser user, String currentWorkspaceId) {
        if (currentWorkspaceId == null || currentWorkspaceId.isBlank()) {
            return Set.of();
        }
        Workspace workspace = workspaceRepository.findById(currentWorkspaceId.strip()).orElse(null);
        if (workspace == null) {
            return Set.of();
        }
        Set<String> readable = new LinkedHashSet<>();
        readable.add(workspace.getId());
        if (workspace.getType() == WorkspaceType.ORG) {
            collectDescendants(readable, workspace.getId());
        } else {
            Workspace ancestor = workspace.getParent();
            while (ancestor != null) {
                readable.add(ancestor.getId());
                ancestor = ancestor.getParent();
            }
        }
        return readable;
    }

    private void collectDescendants(Set<String> accumulator, String parentId) {
        for (Workspace child : workspaceRepository.findByParentId(parentId)) {
            if (accumulator.add(child.getId())) {
                collectDescendants(accumulator, child.getId());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> members(AppUser actor, String workspaceId) {
        requireMemberOrPublicAdmin(actor, workspaceId);
        return memberRepository.findAllByWorkspaceIdOrderByJoinedAtAsc(workspaceId).stream()
                .map(this::memberResponse)
                .toList();
    }

    @Transactional
    public WorkspaceMemberResponse addMember(AppUser actor, String workspaceId, AddWorkspaceMemberRequest request) {
        WorkspaceMember actorMembership = requireOwner(actor, workspaceId);
        if (actorMembership.getWorkspace().getType() == WorkspaceType.PERSONAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "个人空间不能添加成员");
        }
        if (request.role() == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能添加第二个空间所有者");
        }

        AppUser member = userRepository.findByAccount(request.account().trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (memberRepository.findByWorkspaceIdAndUserId(workspaceId, member.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户已经是空间成员");
        }
        return memberResponse(memberRepository.save(new WorkspaceMember(actorMembership.getWorkspace(), member, request.role())));
    }

    @Transactional
    public WorkspaceMemberResponse changeRole(
            AppUser actor,
            String workspaceId,
            String memberPublicId,
            UpdateWorkspaceMemberRoleRequest request
    ) {
        requireOwner(actor, workspaceId);
        if (request.role() == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂不支持转移空间所有权");
        }
        WorkspaceMember member = memberByPublicId(workspaceId, memberPublicId);
        if (member.getRole() == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修改空间所有者角色");
        }
        member.changeRole(request.role());
        return memberResponse(member);
    }

    @Transactional
    public void removeMember(AppUser actor, String workspaceId, String memberPublicId) {
        requireOwner(actor, workspaceId);
        WorkspaceMember member = memberByPublicId(workspaceId, memberPublicId);
        if (member.getRole() == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能移除空间所有者");
        }
        String scopePrefix = UserConversationScope.id(member.getUser(), workspaceId + ":");
        if (executionRegistry != null) {
            executionRegistry.cancelByPrefix(scopePrefix);
        }
        if (conversationMemory != null) {
            conversationMemory.removeByPrefix(scopePrefix);
        }
        memberRepository.delete(member);
    }

    private WorkspaceResponse create(AppUser owner, String name, WorkspaceType type) {
        Workspace workspace = workspaceRepository.save(new Workspace(name.strip(), type, owner));
        WorkspaceMember membership = memberRepository.save(new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER));
        return response(membership);
    }

    private void ensurePersonalWorkspace(AppUser user) {
        if (workspaceRepository.findByOwnerIdAndType(user.getId(), WorkspaceType.PERSONAL).isPresent()) {
            return;
        }
        Workspace workspace = workspaceRepository.save(new Workspace(
                "personal-" + user.getId(), user.getUserName() + "的个人空间", WorkspaceType.PERSONAL, user));
        memberRepository.save(new WorkspaceMember(workspace, user, WorkspaceRole.OWNER));
    }

    private WorkspaceMember requireMember(AppUser user, String workspaceId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识空间不存在"));
    }

    private void requireMemberOrPublicAdmin(AppUser user, String workspaceId) {
        if (memberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId()).isPresent()) {
            return;
        }
        boolean publicWorkspace = workspaceRepository.findById(workspaceId)
                .map(workspace -> workspace.getType() == WorkspaceType.PUBLIC)
                .orElse(false);
        if (!publicWorkspace || !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识空间不存在");
        }
    }

    private boolean isAdmin(AppUser user) {
        return user.getSystemRole() == SystemRole.ADMIN || user.getSystemRole() == SystemRole.SUPER_ADMIN;
    }

    private boolean isSuperAdmin(AppUser user) {
        return user.getSystemRole() == SystemRole.SUPER_ADMIN;
    }

    private WorkspaceMember requireOwner(AppUser user, String workspaceId) {
        WorkspaceMember membership = requireMember(user, workspaceId);
        if (membership.getRole() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有空间所有者可以管理成员");
        }
        return membership;
    }

    private WorkspaceMember memberByPublicId(String workspaceId, String publicId) {
        AppUser user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "空间成员不存在"));
        return memberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "空间成员不存在"));
    }

    private WorkspaceResponse response(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getType(), role,
                workspace.getCreatedAt(), workspace.getParent() == null ? null : workspace.getParent().getId());
    }

    private WorkspaceResponse response(WorkspaceMember membership) {
        Workspace workspace = membership.getWorkspace();
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getType(),
                membership.getRole(), workspace.getCreatedAt(),
                workspace.getParent() == null ? null : workspace.getParent().getId());
    }

    private WorkspaceMemberResponse memberResponse(WorkspaceMember membership) {
        AppUser user = membership.getUser();
        return new WorkspaceMemberResponse(user.getPublicId(), user.getUserName(), membership.getRole(), membership.getJoinedAt());
    }
}
