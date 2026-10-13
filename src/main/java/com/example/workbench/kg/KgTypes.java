package com.example.workbench.kg;

/**
 * 知识图谱实体/关系类型的字符串常量。集中定义，避免拼写漂移。
 */
public final class KgTypes {

    public static final String ENTITY_WIKI_PAGE = "WIKI_PAGE";
    public static final String ENTITY_TERM = "TERM";

    public static final String REL_WIKI_LINK = "WIKI_LINK";
    public static final String REL_PARENT_CHILD = "PARENT_CHILD";
    public static final String REL_MENTIONS = "MENTIONS";

    /** wiki 页实体的确定性 id 前缀。 */
    public static final String WIKI_ENTITY_PREFIX = "wiki:";
    /** 概念 term 实体的确定性 id 前缀。 */
    public static final String TERM_ENTITY_PREFIX = "term:";

    private KgTypes() {
    }

    public static String wikiEntityId(String pageId) {
        return WIKI_ENTITY_PREFIX + pageId;
    }

    public static String termEntityId(String termSlug) {
        return TERM_ENTITY_PREFIX + termSlug;
    }
}
