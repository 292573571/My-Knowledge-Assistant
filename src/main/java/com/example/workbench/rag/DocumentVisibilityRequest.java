package com.example.workbench.rag;

import com.example.workbench.workspace.DocumentVisibility;
import jakarta.validation.constraints.NotNull;

public record DocumentVisibilityRequest(@NotNull DocumentVisibility visibility) {
}
