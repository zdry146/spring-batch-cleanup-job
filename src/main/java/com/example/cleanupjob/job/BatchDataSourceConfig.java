package com.example.cleanupjob.job;

import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableJdbcJobRepository
public class BatchDataSourceConfig extends JdbcDefaultBatchConfiguration {

    private final DataSource dataSource;

    public BatchDataSourceConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected DataSource getDataSource() {
        return this.dataSource;
    }
}
