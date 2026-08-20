package com.example.workbench.observability;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.zaxxer.hikari.HikariPoolMXBean;

@Component
public class DatabasePoolMetrics {

    public DatabasePoolMetrics(MeterRegistry registry, ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (!(dataSource instanceof HikariDataSource hikari)) return;
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) return;
        registry.gauge("db.pool.active", pool, HikariPoolMXBean::getActiveConnections);
        registry.gauge("db.pool.idle", pool, HikariPoolMXBean::getIdleConnections);
        registry.gauge("db.pool.max", hikari, HikariDataSource::getMaximumPoolSize);
        registry.gauge("db.pool.pending", pool, HikariPoolMXBean::getThreadsAwaitingConnection);
    }
}
