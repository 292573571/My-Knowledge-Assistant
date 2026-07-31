package com.example.workbench.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 在 Spring AI 初始化向量存储前创建并确认 Chroma collection 可用。
 */
@Component
public class ChromaCollectionInitializer implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChromaCollectionInitializer.class);
    private static final int COLLECTION_READ_ATTEMPTS = 20;
    private static final long COLLECTION_READ_DELAY_MILLIS = 100L;

    private final ObjectProvider<ChromaApi> chromaApiProvider;
    private final ObjectProvider<ChromaVectorStoreProperties> propertiesProvider;

    public ChromaCollectionInitializer(
            ObjectProvider<ChromaApi> chromaApiProvider,
            ObjectProvider<ChromaVectorStoreProperties> propertiesProvider
    ) {
        this.chromaApiProvider = chromaApiProvider;
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if ("vectorStore".equals(beanName)) {
            ensureCollectionExists();
        }

        return bean;
    }

    private void ensureCollectionExists() {
        ChromaApi chromaApi = chromaApiProvider.getIfAvailable();
        ChromaVectorStoreProperties properties = propertiesProvider.getIfAvailable();

        if (chromaApi == null || properties == null) {
            log.warn("Chroma API or properties are not available, skip collection initialization");
            return;
        }

        String tenantName = properties.getTenantName();
        String databaseName = properties.getDatabaseName();
        String collectionName = properties.getCollectionName();

        if (chromaApi.getTenant(tenantName) == null) {
            chromaApi.createTenant(tenantName);
        }

        if (chromaApi.getDatabase(tenantName, databaseName) == null) {
            chromaApi.createDatabase(tenantName, databaseName);
        }

        try {
            chromaApi.getCollection(tenantName, databaseName, collectionName);
            return;
        } catch (RuntimeException exception) {
            if (!isNotFound(exception)) {
                throw exception;
            }
        }

        log.info("Creating Chroma collection: {}", collectionName);
        chromaApi.createCollection(
                tenantName,
                databaseName,
                new ChromaApi.CreateCollectionRequest(collectionName)
        );
        awaitCollection(chromaApi, tenantName, databaseName, collectionName);
    }

    private void awaitCollection(ChromaApi chromaApi, String tenantName, String databaseName, String collectionName) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= COLLECTION_READ_ATTEMPTS; attempt++) {
            try {
                chromaApi.getCollection(tenantName, databaseName, collectionName);
                return;
            } catch (RuntimeException exception) {
                if (!isNotFound(exception)) {
                    throw exception;
                }
                lastFailure = exception;
                if (attempt < COLLECTION_READ_ATTEMPTS) {
                    sleepBeforeRetry();
                }
            }
        }
        throw new IllegalStateException("Chroma collection 创建后仍不可用: " + collectionName, lastFailure);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(COLLECTION_READ_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Chroma collection 可用时被中断", exception);
        }
    }

    private boolean isNotFound(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpClientErrorException httpException
                    && httpException.getStatusCode().value() == 404) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
