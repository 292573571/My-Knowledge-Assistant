package com.example.workbench.eval;

import java.util.Locale;

/**
 * 评测用例所属的质量集合。
 */
public enum EvalSuite {
    SMOKE,
    REGRESSION,
    FORMAT,
    SECURITY,
    FAILURE,
    NO_ANSWER,
    /** 由真实文档自动生成、等待人工审核的草稿题。 */
    DRAFT;

    /**
     * 将外部输入转换为评测集合，空值和未知值兼容为回归集合。
     *
     * @param value 外部输入
     * @return 规范化后的评测集合
     */
    public static EvalSuite from(String value) {
        if (value == null || value.isBlank()) {
            return REGRESSION;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return REGRESSION;
        }
    }
}
