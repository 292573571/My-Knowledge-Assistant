package com.example.workbench.kg;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KgRelationRepository extends JpaRepository<KgRelation, String> {

    List<KgRelation> findByWorkspaceIdOrderByRelationTypeAsc(String workspaceId);

    void deleteByWorkspaceIdAndEvidenceSourceId(String workspaceId, String evidenceSourceId);

    Optional<KgRelation> findByWorkspaceIdAndSourceEntityIdAndTargetEntityIdAndRelationType(
            String workspaceId, String sourceEntityId, String targetEntityId, String relationType);

    List<KgRelation> findByWorkspaceIdAndTargetEntityId(String workspaceId, String targetEntityId);
}
