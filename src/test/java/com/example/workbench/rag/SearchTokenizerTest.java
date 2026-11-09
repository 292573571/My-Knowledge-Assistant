package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTokenizerTest {

    private final SearchTokenizer tokenizer = new SearchTokenizer();

    @Test
    void segmentsChineseQueryIntoWords() {
        List<String> words = tokenizer.words("准备工作中需要先生成什么？");

        // jieba 词典未收录「准备工作」，靠二字组补充：准备/工作/生成 必须在列
        assertThat(words).contains("准备", "工作", "生成", "先生");
        assertThat(words).doesNotContain("什么");
    }

    @Test
    void keepsLatinWordsLowercased() {
        List<String> words = tokenizer.words("Flyway 的 V30 占位符 ${db.url} JAR包");

        assertThat(words).contains("flyway", "v30", "占位", "jar");
    }

    @Test
    void tokenizeJoinsWithSpacesAndFiltersNoise() {
        String tokens = tokenizer.tokenize("作业可以包含多个作业项！");

        assertThat(tokens).doesNotContain("，").doesNotContain("！");
        assertThat(tokens.split("\\s+")).allMatch(word -> word.length() >= 2);
    }

    @Test
    void blankInputYieldsEmpty() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize("   ")).isEmpty();
        assertThat(tokenizer.words("。。。")).isEmpty();
    }
}
