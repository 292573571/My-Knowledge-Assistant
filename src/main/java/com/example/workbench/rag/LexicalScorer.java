package com.example.workbench.rag;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 词法匹配的纯函数集合：从 RagService 原样抽取，行为未做任何调整。
 *
 * <p>原先这套逻辑同时服务于「重排打分」和「知识是否充分」两条链路，
 * 抽取后由 {@link RetrievalRanker} 与 RagService 共用，避免出现两份逐渐走偏的实现。
 */
final class LexicalScorer {

    private static final Set<String> WEAK_TERMS = Set.of(
            "什么", "怎么", "怎样", "如何", "哪些", "哪个", "是否", "能否", "可以",
            "为什", "么是", "是什", "么样", "么办", "多少", "几个", "有无", "哪有"
    );

    private static final Pattern MATCH_TERM = Pattern.compile(
            "[\\p{IsHan}]{2,}|[a-z][a-z0-9_-]{1,}", Pattern.CASE_INSENSITIVE);

    private static final Pattern SIGNIFICANT_TERM = Pattern.compile(
            "[\\p{IsHan}]{2,}|[a-z][a-z0-9_-]{2,}", Pattern.CASE_INSENSITIVE);

    private LexicalScorer() {
    }

    /** 问题词元在正文中的命中得分：命中长度 ≥3 记 3 分，否则记 2 分。 */
    static int matchScore(String question, String content) {
        if (question == null || content == null) {
            return 0;
        }

        String normalizedContent = content.toLowerCase(Locale.ROOT);
        int score = 0;
        var matcher = MATCH_TERM.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            int matchedLength = longestMatchedLength(matcher.group().toLowerCase(Locale.ROOT), normalizedContent);
            if (matchedLength > 0) {
                score += matchedLength >= 3 ? 3 : 2;
            }
        }
        return score;
    }

    /** 问题与正文是否存在有意义的共同词（用于「知识是否充分」的快速判定）。 */
    static boolean sharesSignificantTerm(String question, String content) {
        if (question == null || content == null) {
            return false;
        }
        var matcher = SIGNIFICANT_TERM.matcher(question);
        while (matcher.find()) {
            String term = matcher.group().toLowerCase(Locale.ROOT);
            if (longestMatchedLength(term, content) >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回 term 在 content 中实际命中的最长子串长度，0 表示未命中。
     *
     * <p>中文没有空格分词，正则 {@code [\p{IsHan}]{2,}} 是贪婪的，会把「什么是缓存」
     * 整句当成一个 term 去 content 里 contains，自然永远命中不了——短问题的词法判定
     * 因此长期失效，检索到内容也被判成「知识不足」而转模型兜底。
     * 这里对含汉字的 term 按「最长优先」切成 2..N 字窗口逐个尝试，切到「缓存」这类真词即可命中。
     * 英文与数字保持整词匹配，避免 "is"、"at" 这类碎片造成误命中。</p>
     */
    static int longestMatchedLength(String term, String content) {
        if (content.contains(term)) {
            return term.length();
        }
        if (term.length() <= 2 || !containsHan(term)) {
            return 0;
        }
        for (int size = term.length() - 1; size >= 2; size--) {
            for (int start = 0; start + size <= term.length(); start++) {
                String window = term.substring(start, start + size);
                if (!WEAK_TERMS.contains(window) && content.contains(window)) {
                    return size;
                }
            }
        }
        return 0;
    }

    private static boolean containsHan(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }
}
