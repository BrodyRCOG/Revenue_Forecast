package com.cognizant.revintel;

import com.cognizant.revintel.config.DataBootstrap;
import com.cognizant.revintel.repository.DataCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot smoke test: the context starts, schema exists, and *some* dataset is loaded -- either the
 * generated one or the fallback seed. Deliberately agnostic about which, so the test suite passes
 * on a clean checkout before anyone has run the Python generator.
 */
@SpringBootTest
class ApplicationBootTest {

    @Autowired
    private DataCatalogRepository catalog;

    @Autowired
    private DataBootstrap bootstrap;

    @Test
    void contextLoadsAndDatasetIsNonEmpty() {
        assertThat(catalog.catalog()).hasSize(8);
        assertThat(catalog.totalRows()).isPositive();
        assertThat(catalog.catalog())
                .allSatisfy(row -> assertThat(row.rowCount())
                        .as("table %s must not be empty", row.tableName())
                        .isPositive());
    }

    @Test
    void reportsWhichDatasetItIsRunningOn() {
        // No assertion on the value -- just that the flag is observable for the /api/evals payload.
        boolean fallback = bootstrap.isUsingFallbackDataset();
        assertThat(fallback).isIn(true, false);
    }
}
