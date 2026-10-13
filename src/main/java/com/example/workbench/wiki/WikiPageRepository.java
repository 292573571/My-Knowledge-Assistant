package com.example.workbench.wiki;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WikiPageRepository extends JpaRepository<WikiPageEntity, String> {

    List<WikiPageEntity> findByWorkspaceIdOrderByTitleAsc(String workspaceId);

    Optional<WikiPageEntity> findByWorkspaceIdAndSlug(String workspaceId, String slug);

    long countByWorkspaceIdAndSlug(String workspaceId, String slug);

    long countByWorkspaceIdAndSlugAndIdNot(String workspaceId, String slug, String id);
}
