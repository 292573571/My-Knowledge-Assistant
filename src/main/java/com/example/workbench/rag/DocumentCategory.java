package com.example.workbench.rag;

/**
 * 文档分类常量与判定。
 *
 * <p>分类决定文档能否进入知识库事实来源：学习记录（及其提升出的正式笔记）只服务学习记录页，
 * 不能参与 RAG 检索，否则会用 AI 生成内容冒充本地知识。
 *
 * <p>路径前缀与分类值的映射集中在此，避免同一判定散落到摄入、入库、检索多处后产生漂移。
 */
public final class DocumentCategory {

    public static final String SOURCE = "SOURCE";
    public static final String LEARNING_RECORD = "LEARNING_RECORD";
    public static final String FORMAL_NOTE = "FORMAL_NOTE";

    public static final String LEARNING_RECORD_PREFIX = "docs/learning-records/";
    public static final String MANUAL_NOTE_PREFIX = "docs/manual-notes/";

    private static final String PROMOTED_LEARNING_NOTE_SUFFIX = ".*/\\d{4}-\\d{2}-\\d{2}-learning-note\\.md$";

    private DocumentCategory() {
    }

    public static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    public static String of(String path) {
        String normalized = normalizePath(path);
        if (normalized.startsWith(LEARNING_RECORD_PREFIX)) {
            return LEARNING_RECORD;
        }
        if (normalized.startsWith(MANUAL_NOTE_PREFIX)) {
            return FORMAL_NOTE;
        }
        return SOURCE;
    }

    public static boolean isLearningRecord(String category, String path) {
        return LEARNING_RECORD.equals(category) || normalizePath(path).startsWith(LEARNING_RECORD_PREFIX);
    }

    public static boolean isPromotedLearningNote(String category, String path) {
        return FORMAL_NOTE.equals(category) && normalizePath(path).matches(PROMOTED_LEARNING_NOTE_SUFFIX);
    }

    /**
     * 是否属于不应参与 RAG 检索的记录：学习记录本身，以及由学习记录提升出的正式笔记。
     */
    public static boolean isExcludedFromKnowledgeBase(String category, String path) {
        return isLearningRecord(category, path) || isPromotedLearningNote(category, path);
    }
}
