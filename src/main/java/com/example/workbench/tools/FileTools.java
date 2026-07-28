package com.example.workbench.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class FileTools {

    public String readTextFile(Path path) throws IOException {
        return Files.readString(path);
    }

    public boolean exists(Path path) {
        return Files.exists(path);
    }
}
