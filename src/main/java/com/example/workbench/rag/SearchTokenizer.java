package com.example.workbench.rag;

import com.huaban.analysis.jieba.SegToken;
import com.huaban.analysis.jieba.JiebaSegmenter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 基于 jieba 的中文分词器：入库侧生成 search_tokens、查询侧生成检索词共用同一套切分，
 * 保证 PostgreSQL simple 配置下 tsquery 与 tsvector 的词形一致。
 * jieba 词典未覆盖的中文长串额外补充相邻二字组，避免 Viterbi 最优路径丢词。
 */
@Component
public class SearchTokenizer {

    private static final Set<String> STOP_WORDS = Set.of("什么", "如何", "为什么", "可以", "是否",
            "这个", "那个", "哪些", "以及", "需要", "应该", "通过", "进行");
    private static final int MIN_TOKEN_LENGTH = 2;
    private static final Pattern HAN_RUN_PATTERN = Pattern.compile("\\p{IsHan}{3,}");

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /**
     * 对文本分词并输出空格拼接的词序列，供 to_tsvector('simple', …) 建索引。
     *
     * @param text 原始文本
     * @return 空格分隔的词序列，无词时返回空串
     */
    public String tokenize(String text) {
        List<String> words = words(text);
        return String.join(" ", words);
    }

    /**
     * 提取检索词：jieba 切分 + 中文长串相邻二字组补充，过滤停用词、单字与空白，
     * 拉丁词小写化，按出现顺序去重。
     *
     * @param text 原始文本
     * @return 去重后的词列表
     */
    public List<String> words(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        Set<String> seen = java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>());
        for (SegToken token : segmenter.process(text, JiebaSegmenter.SegMode.INDEX)) {
            addWord(words, seen, token.word);
        }
        Matcher hanRun = HAN_RUN_PATTERN.matcher(text);
        while (hanRun.find()) {
            String run = hanRun.group();
            for (int index = 0; index < run.length() - 1; index++) {
                addWord(words, seen, run.substring(index, index + 2));
            }
        }
        return words;
    }

    private void addWord(List<String> words, Set<String> seen, String raw) {
        String word = normalized(raw);
        if (word.length() < MIN_TOKEN_LENGTH || STOP_WORDS.contains(word) || !seen.add(word)) {
            return;
        }
        words.add(word);
    }

    private String normalized(String word) {
        String trimmed = word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
        boolean meaningful = false;
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (Character.isLetterOrDigit(current)) {
                meaningful = true;
                break;
            }
        }
        return meaningful ? trimmed : "";
    }
}
