package com.example.workbench.eval;

import java.util.Locale;

/**
 * 评测用例覆盖的 RAG 质量层级。
 */
public enum EvalLayer {
    PARSER,
    RETRIEVAL,
    CONTEXT,
    GENERATION;

    /**
     * 将外部输入转换为评测层级，空值和未知值兼容为生成层评测。
     *
     * @param value 外部输入
     * @return 规范化后的评测层级
     */
    public static EvalLayer from(String value) {
        if (value == null || value.isBlank()) {
            return GENERATION;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return GENERATION;
        }
    }
}
