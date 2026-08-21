package com.example.workbench.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class EvalQuestionSource {

    private static final Path FILESYSTEM_PATH = Path.of("eval", "questions.jsonl");

    private EvalQuestionSource() {
    }

    static List<String> readLines() throws IOException {
        try (InputStream stream = EvalQuestionSource.class.getResourceAsStream("/eval/questions.jsonl")) {
            if (stream != null) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        }
        return Files.readAllLines(FILESYSTEM_PATH, StandardCharsets.UTF_8);
    }
}
