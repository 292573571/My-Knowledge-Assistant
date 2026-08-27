package com.example.workbench.workspace;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    List<WorkspaceMember> findAllByUserIdOrderByWorkspaceCreatedAtAsc(Long userId);

    List<WorkspaceMember> findAllByWorkspaceIdOrderByJoinedAtAsc(String workspaceId);
    Page<WorkspaceMember> findAllByWorkspaceId(String workspaceId, Pageable pageable);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(String workspaceId, Long userId);
}
