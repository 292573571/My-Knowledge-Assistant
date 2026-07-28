package com.example.workbench.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class ChromaCollectionInitializer implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChromaCollectionInitializer.class);

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

        try {
            if (chromaApi.getTenant(tenantName) == null) {
                chromaApi.createTenant(tenantName);
            }

            if (chromaApi.getDatabase(tenantName, databaseName) == null) {
                chromaApi.createDatabase(tenantName, databaseName);
            }

            chromaApi.getCollection(tenantName, databaseName, collectionName);
        } catch (RuntimeException exception) {
            log.info("Creating Chroma collection: {}", collectionName);
            chromaApi.createCollection(
                    tenantName,
                    databaseName,
                    new ChromaApi.CreateCollectionRequest(collectionName)
            );
        }
    }
}
