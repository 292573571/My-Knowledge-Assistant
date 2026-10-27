package com.example.workbench.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebSearchServiceTest {

    @Test
    void primaryResultShortCircuitsSecondary() {
        WebSearchClient primary = mock(WebSearchClient.class);
        WebSearchClient secondary = mock(WebSearchClient.class);
        when(primary.name()).thenReturn("tavily");
        when(primary.isConfigured()).thenReturn(true);
        when(primary.search("q")).thenReturn(List.of(new WebSearchResult("t", "https://a", "s")));
        when(secondary.name()).thenReturn("bocha");
        when(secondary.isConfigured()).thenReturn(true);

        WebSearchService service = new WebSearchService(List.of(secondary, primary), "tavily,bocha");

        assertThat(service.search("q")).hasSize(1);
        verify(secondary, never()).search(anyString());
    }

    @Test
    void fallsBackToSecondaryWhenPrimaryReturnsEmpty() {
        WebSearchClient primary = mock(WebSearchClient.class);
        WebSearchClient secondary = mock(WebSearchClient.class);
        when(primary.name()).thenReturn("tavily");
        when(primary.isConfigured()).thenReturn(true);
        when(primary.search("q")).thenReturn(List.of());
        when(secondary.name()).thenReturn("bocha");
        when(secondary.isConfigured()).thenReturn(true);
        when(secondary.search("q")).thenReturn(List.of(new WebSearchResult("t", "https://b", "s")));

        WebSearchService service = new WebSearchService(List.of(primary, secondary), "tavily,bocha");

        assertThat(service.search("q")).hasSize(1);
        assertThat(service.search("q").get(0).url()).isEqualTo("https://b");
    }

    @Test
    void skipsUnconfiguredProviders() {
        WebSearchClient unconfigured = mock(WebSearchClient.class);
        WebSearchClient fallback = mock(WebSearchClient.class);
        when(unconfigured.name()).thenReturn("tavily");
        when(unconfigured.isConfigured()).thenReturn(false);
        when(fallback.name()).thenReturn("bocha");
        when(fallback.isConfigured()).thenReturn(true);
        when(fallback.search("q")).thenReturn(List.of(new WebSearchResult("t", "https://b", "s")));

        WebSearchService service = new WebSearchService(List.of(unconfigured, fallback), "tavily,bocha");

        assertThat(service.search("q")).hasSize(1);
        verify(unconfigured, never()).search(anyString());
    }

    @Test
    void orderPropertyControlsPriority() {
        WebSearchClient bocha = mock(WebSearchClient.class);
        WebSearchClient tavily = mock(WebSearchClient.class);
        when(bocha.name()).thenReturn("bocha");
        when(bocha.isConfigured()).thenReturn(true);
        when(bocha.search("q")).thenReturn(List.of(new WebSearchResult("t", "https://bocha", "s")));
        when(tavily.name()).thenReturn("tavily");
        when(tavily.isConfigured()).thenReturn(true);
        when(tavily.search("q")).thenReturn(List.of(new WebSearchResult("t", "https://tavily", "s")));

        WebSearchService bochaFirst = new WebSearchService(List.of(tavily, bocha), "bocha,tavily");
        assertThat(bochaFirst.providerOrder()).containsExactly("bocha", "tavily");
        assertThat(bochaFirst.search("q").get(0).url()).isEqualTo("https://bocha");

        WebSearchService defaultOrder = new WebSearchService(List.of(tavily, bocha), "tavily,bocha");
        assertThat(defaultOrder.providerOrder()).containsExactly("tavily", "bocha");
    }

    @Test
    void blankQueryReturnsEmptyWithoutCallingProviders() {
        WebSearchClient primary = mock(WebSearchClient.class);
        when(primary.name()).thenReturn("tavily");
        when(primary.isConfigured()).thenReturn(true);

        WebSearchService service = new WebSearchService(List.of(primary), "tavily");

        assertThat(service.search("   ")).isEmpty();
        verify(primary, never()).search(anyString());
    }

    @Test
    void allEmptyReturnsEmpty() {
        WebSearchClient primary = mock(WebSearchClient.class);
        WebSearchClient secondary = mock(WebSearchClient.class);
        when(primary.name()).thenReturn("tavily");
        when(primary.isConfigured()).thenReturn(true);
        when(primary.search("q")).thenReturn(List.of());
        when(secondary.name()).thenReturn("bocha");
        when(secondary.isConfigured()).thenReturn(true);
        when(secondary.search("q")).thenReturn(List.of());

        WebSearchService service = new WebSearchService(List.of(primary, secondary), "tavily,bocha");

        assertThat(service.search("q")).isEmpty();
    }
}
