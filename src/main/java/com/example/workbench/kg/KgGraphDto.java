package com.example.workbench.kg;

import java.util.List;

/**
 * 图谱快照：节点 + 边，供前端力导向图渲染。
 */
public class KgGraphDto {

    private List<KgNodeDto> nodes;
    private List<KgEdgeDto> edges;

    public KgGraphDto(List<KgNodeDto> nodes, List<KgEdgeDto> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    public List<KgNodeDto> getNodes() {
        return nodes;
    }

    public List<KgEdgeDto> getEdges() {
        return edges;
    }

    /** 图谱节点。 */
    public static class KgNodeDto {
        private String id;
        private String type;
        private String name;
        private String sourceId;
        private int degree;

        public KgNodeDto(String id, String type, String name, String sourceId, int degree) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.sourceId = sourceId;
            this.degree = degree;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getName() {
            return name;
        }

        public String getSourceId() {
            return sourceId;
        }

        public int getDegree() {
            return degree;
        }
    }

    /** 图谱边。 */
    public static class KgEdgeDto {
        private String source;
        private String target;
        private String type;
        private int weight;

        public KgEdgeDto(String source, String target, String type, int weight) {
            this.source = source;
            this.target = target;
            this.type = type;
            this.weight = weight;
        }

        public String getSource() {
            return source;
        }

        public String getTarget() {
            return target;
        }

        public String getType() {
            return type;
        }

        public int getWeight() {
            return weight;
        }
    }
}
