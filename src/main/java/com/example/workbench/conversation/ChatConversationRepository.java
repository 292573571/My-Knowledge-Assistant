package com.example.workbench.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, String> {
    @Query("select c from ChatConversation c where c.user.id = :userId and "
            + "(c.workspaceId = :workspaceId or (c.workspaceId is null and :workspaceId = :personalWorkspaceId)) "
            + "order by c.updatedAt desc, c.id desc")
    List<ChatConversation> findVisibleByUserAndWorkspace(@Param("userId") Long userId,
                                                         @Param("workspaceId") String workspaceId,
                                                         @Param("personalWorkspaceId") String personalWorkspaceId);

    @Query("select c from ChatConversation c where c.user.id = :userId and "
            + "(c.workspaceId = :workspaceId or (c.workspaceId is null and :workspaceId = :personalWorkspaceId))")
    Page<ChatConversation> findVisibleByUserAndWorkspace(@Param("userId") Long userId,
                                                         @Param("workspaceId") String workspaceId,
                                                         @Param("personalWorkspaceId") String personalWorkspaceId,
                                                         Pageable pageable);

    @Query("select c from ChatConversation c where (c.clientConversationId = :id "
            + "or (c.clientConversationId is null and c.id = :id)) and c.user.id = :userId and "
            + "(c.workspaceId = :workspaceId or (c.workspaceId is null and :workspaceId = :personalWorkspaceId))")
    Optional<ChatConversation> findVisibleByIdAndUserAndWorkspace(@Param("id") String id,
                                                                  @Param("userId") Long userId,
                                                                  @Param("workspaceId") String workspaceId,
                                                                  @Param("personalWorkspaceId") String personalWorkspaceId);
}
