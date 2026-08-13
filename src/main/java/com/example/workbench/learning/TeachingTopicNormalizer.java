package com.example.workbench.learning;

import java.util.Locale;

public final class TeachingTopicNormalizer {

    private TeachingTopicNormalizer() {
    }

    public static String display(String topic) {
        return topic == null ? "" : topic.strip().replaceAll("\\s+", " ");
    }

    public static String key(String topic) {
        return display(topic).toLowerCase(Locale.ROOT);
    }
}
