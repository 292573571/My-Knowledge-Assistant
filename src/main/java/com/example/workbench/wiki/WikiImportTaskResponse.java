package com.example.workbench.wiki;

/**
 * Wiki 导入任务的查询结果。
 *
 * <p>RUNNING 时 content 字段为空；SUCCEEDED 后返回解析出的草稿；FAILED 时带 error。
 */
public record WikiImportTaskResponse(
        String taskId,
        String status,
        String fileName,
        String title,
        String contentMd,
        String error
) {

    public static WikiImportTaskResponse running(String taskId) {
        return new WikiImportTaskResponse(taskId, "RUNNING", null, null, null, null);
    }

    public static WikiImportTaskResponse failed(String taskId, String fileName, String error) {
        return new WikiImportTaskResponse(taskId, "FAILED", fileName, null, null, error);
    }

    public static WikiImportTaskResponse succeeded(String taskId, WikiImportContent content) {
        return new WikiImportTaskResponse(taskId, "SUCCEEDED", content.fileName(), content.title(), content.contentMd(), null);
    }
}
