package com.example.workbench.workspace;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    Optional<Workspace> findByOwnerIdAndType(Long ownerId, WorkspaceType type);

    List<Workspace> findAllByTypeOrderByCreatedAtAsc(WorkspaceType type);

    List<Workspace> findByParentId(String parentId);

    Optional<Workspace> findByNameAndType(String name, WorkspaceType type);
}
