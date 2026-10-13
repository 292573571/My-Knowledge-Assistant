package com.example.workbench.kg;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface KgEntityRepository extends JpaRepository<KgEntity, String> {

    List<KgEntity> findByWorkspaceIdOrderByEntityTypeAscNameAsc(String workspaceId);

    Optional<KgEntity> findByWorkspaceIdAndEntityTypeAndName(String workspaceId, String entityType, String name);

    long countByWorkspaceId(String workspaceId);

    /**
     * 删除指定空间内已无任何关系引用的孤立 TERM 节点（页面删除后调用，避免概念悬空）。
     */
    @Modifying
    @Query(value = """
            DELETE FROM kg_entities e
            WHERE e.workspace_id = :ws
              AND e.entity_type = 'TERM'
              AND e.id NOT IN (SELECT r.source_entity_id FROM kg_relations r WHERE r.workspace_id = :ws)
              AND e.id NOT IN (SELECT r.target_entity_id FROM kg_relations r WHERE r.workspace_id = :ws)
            """, nativeQuery = true)
    int deleteOrphanTerms(@Param("ws") String workspaceId);
}
