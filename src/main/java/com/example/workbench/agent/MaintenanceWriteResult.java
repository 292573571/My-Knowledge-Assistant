package com.example.workbench.agent;

import java.util.List;

public record MaintenanceWriteResult(String answer, MaintenanceAction action, String taskId, boolean readOnly,
                                     List<MaintenanceTaskReference> tasks) {

    public MaintenanceWriteResult(String answer, MaintenanceAction action, String taskId, boolean readOnly) {
        this(answer, action, taskId, readOnly, List.of());
    }
}
