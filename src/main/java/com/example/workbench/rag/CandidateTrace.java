package com.example.workbench.rag;

import java.util.List;

/** 检索候选的融合轨迹，供排序、阈值判定与检索调试共用。 */
record CandidateTrace(
        String channel, Double denseScore, Double sparseScore, double fusionScore,
        Integer denseRank, Integer sparseRank, int finalRank, List<String> matchedQueries
) {
}
