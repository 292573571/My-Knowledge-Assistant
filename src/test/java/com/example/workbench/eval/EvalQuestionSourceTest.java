package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvalQuestionSourceTest {

    @Test
    void readsBundledQuestionBank() throws Exception {
        List<String> lines = EvalQuestionSource.readLines();

        assertThat(lines).hasSize(36);
        assertThat(lines.get(0)).contains("flow-001");
    }
}
