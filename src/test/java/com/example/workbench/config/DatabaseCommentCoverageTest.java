package com.example.workbench.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.annotations.Comment;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

class DatabaseCommentCoverageTest {

    @Test
    void everyEntityAndPersistentFieldHasAChineseDatabaseComment() throws ClassNotFoundException {
        Set<Class<?>> entities = entityClasses();
        assertThat(entities).isNotEmpty();
        for (Class<?> entity : entities) {
            assertChineseComment(entity.getAnnotation(Comment.class), entity.getSimpleName());
            for (Field field : entity.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)
                        || field.isAnnotationPresent(Column.class)
                        || field.isAnnotationPresent(JoinColumn.class)) {
                    assertChineseComment(field.getAnnotation(Comment.class),
                            entity.getSimpleName() + "." + field.getName());
                }
            }
        }
    }

    private Set<Class<?>> entityClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<Class<?>> entities = new java.util.HashSet<>();
        for (var candidate : scanner.findCandidateComponents("com.example.workbench")) {
            entities.add(Class.forName(candidate.getBeanClassName()));
        }
        return entities.stream().collect(Collectors.toUnmodifiableSet());
    }

    private void assertChineseComment(Comment comment, String target) {
        assertThat(comment)
                .as("数据库映射 %s 应声明 @Comment", target)
                .isNotNull();
        assertThat(comment.value())
                .as("数据库映射 %s 应使用中文注释", target)
                .containsPattern("[\\u4e00-\\u9fff]");
    }
}
