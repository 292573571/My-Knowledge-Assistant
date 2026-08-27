package com.example.workbench.pagination;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static <T> PageResponse<T> of(List<T> values, Integer requestedPage, Integer requestedSize) {
        int page = requestedPage == null || requestedPage < 0 ? 0 : requestedPage;
        int size = requestedSize == null || requestedSize < 1 || requestedSize > 500 ? 100 : requestedSize;
        long total = values.size();
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        int from = Math.min((int) Math.min((long) page * size, total), values.size());
        int to = Math.min(from + size, values.size());
        return new PageResponse<>(List.copyOf(values.subList(from, to)), page, size, total, totalPages,
                page + 1 < totalPages);
    }
}
