package com.example.workbench.workspace;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AppUserRepository;
import com.example.workbench.auth.SystemRole;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等地把知识空间升级为「组织 → 团队」两级层级。
 *
 * <ul>
 *   <li>若不存在名为 {@code sunline} 的根组织，则用超管（或管理员）作为所有者创建它；</li>
 *   <li>把现有所有「无上级」的团队空间挂到该根组织之下，使根组织文档对所有团队可见、团队文档仅本团队可见。</li>
 * </ul>
 *
 * 该初始化器仅在非测试环境运行，且对所有数据均为幂等操作。
 */
@Component
@Profile("!test")
public class WorkspaceHierarchyInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceHierarchyInitializer.class);
    private static final String ROOT_ORG_NAME = "sunline";

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final AppUserRepository userRepository;

    public WorkspaceHierarchyInitializer(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository memberRepository,
            AppUserRepository userRepository
    ) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Workspace root = workspaceRepository.findAllByTypeOrderByCreatedAtAsc(WorkspaceType.ORG).stream()
                .filter(workspace -> ROOT_ORG_NAME.equals(workspace.getName()))
                .findFirst()
                .orElse(null);

        if (root == null) {
            AppUser owner = userRepository.findFirstBySystemRole(SystemRole.SUPER_ADMIN)
                    .or(() -> userRepository.findFirstBySystemRole(SystemRole.ADMIN))
                    .orElse(null);
            if (owner == null) {
                log.warn("未找到超级管理员或管理员账户，跳过根组织 {} 的自动初始化", ROOT_ORG_NAME);
                return;
            }
            root = workspaceRepository.save(new Workspace(ROOT_ORG_NAME, WorkspaceType.ORG, owner));
            memberRepository.save(new WorkspaceMember(root, owner, WorkspaceRole.OWNER));
            log.info("已创建根组织 {}", ROOT_ORG_NAME);
        }

        for (Workspace candidate : workspaceRepository.findAll()) {
            if (candidate.getType() == WorkspaceType.TEAM
                    && candidate.getParent() == null
                    && !root.getId().equals(candidate.getId())) {
                candidate.setParent(root);
                workspaceRepository.save(candidate);
                log.info("已将团队空间 {} 挂到根组织 {}", candidate.getName(), ROOT_ORG_NAME);
            }
        }
    }
}
