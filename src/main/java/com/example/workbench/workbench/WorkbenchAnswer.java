package com.example.workbench.workbench;

import com.example.workbench.rag.RagSource;
import java.util.List;

public record WorkbenchAnswer(
        String answer,
        List<RagSource> sources
) {
}
