package com.example.workbench.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void readsFilesOnlyFromAllowedRootsAndHonorsSizeLimit() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path file = Files.writeString(allowed.resolve("note.txt"), "knowledge");
        FileTools tools = new FileTools(allowed.toString(), 20);

        assertThat(tools.readTextFile(file)).isEqualTo("knowledge");
        assertThat(tools.exists(file)).isTrue();
        assertThatThrownBy(() -> tools.readTextFile(tempDir.resolve("outside.txt")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("允许目录");
        Path large = Files.writeString(allowed.resolve("large.txt"), "012345678901234567890");
        assertThatThrownBy(() -> tools.readTextFile(large))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("大小限制");
    }

    @Test
    void rejectsSymlinkPath() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path outside = Files.writeString(tempDir.resolve("secret.txt"), "secret");
        Path link = allowed.resolve("link.txt");
        Files.createSymbolicLink(link, outside);
        FileTools tools = new FileTools(allowed.toString(), 100);

        assertThatThrownBy(() -> tools.readTextFile(link))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("允许目录");
    }
}
