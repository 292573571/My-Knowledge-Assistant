package com.example.workbench.workspace;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    List<WorkspaceMember> findAllByUserIdOrderByWorkspaceCreatedAtAsc(Long userId);

    List<WorkspaceMember> findAllByWorkspaceIdOrderByJoinedAtAsc(String workspaceId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(String workspaceId, Long userId);
}
