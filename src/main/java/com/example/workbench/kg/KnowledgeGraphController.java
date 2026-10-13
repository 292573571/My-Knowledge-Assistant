package com.example.workbench.kg;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kg")
public class KnowledgeGraphController {

    private final KgEntityRepository entityRepository;
    private final KgRelationRepository relationRepository;
    private final WorkspaceService workspaceService;

    public KnowledgeGraphController(KgEntityRepository entityRepository,
                                    KgRelationRepository relationRepository,
                                    WorkspaceService workspaceService) {
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
        this.workspaceService = workspaceService;
    }

    /**
     * 返回某知识空间的知识图谱快照（节点 + 边）。前端用于力导向可视化与跳转。
     */
    @GetMapping("/graph")
    public KgGraphDto graph(@RequestParam String workspaceId, HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        workspaceService.access(user, workspaceId);

        List<KgEntity> entities = entityRepository.findByWorkspaceIdOrderByEntityTypeAscNameAsc(workspaceId);
        List<KgRelation> relations = relationRepository.findByWorkspaceIdOrderByRelationTypeAsc(workspaceId);

        Map<String, Integer> degree = new HashMap<>();
        for (KgRelation rel : relations) {
            degree.merge(rel.getSourceEntityId(), 1, Integer::sum);
            degree.merge(rel.getTargetEntityId(), 1, Integer::sum);
        }

        List<KgGraphDto.KgNodeDto> nodes = new ArrayList<>(entities.size());
        for (KgEntity e : entities) {
            nodes.add(new KgGraphDto.KgNodeDto(
                    e.getId(), e.getEntityType(), e.getName(), e.getSourceId(),
                    degree.getOrDefault(e.getId(), 0)));
        }

        List<KgGraphDto.KgEdgeDto> edges = new ArrayList<>(relations.size());
        for (KgRelation r : relations) {
            edges.add(new KgGraphDto.KgEdgeDto(
                    r.getSourceEntityId(), r.getTargetEntityId(), r.getRelationType(), r.getWeight()));
        }

        return new KgGraphDto(nodes, edges);
    }
}
