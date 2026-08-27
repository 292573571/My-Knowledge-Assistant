package com.example.workbench.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {
    @Test
    void slicesPageAndReportsMetadata() {
        PageResponse<String> response = PageResponse.of(List.of("a", "b", "c"), 1, 2);

        assertThat(response.items()).containsExactly("c");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void invalidPagingValuesUseSafeDefaults() {
        PageResponse<String> response = PageResponse.of(List.of("a"), -1, 0);

        assertThat(response.items()).containsExactly("a");
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(100);
    }
}
