package com.cognizant.revintel.repository.projection;

/** One row of the Data Sources tab: what a table holds, where it came from, how big it is. */
public record DataCatalogRow(
        String tableName,
        String label,
        String dataType,
        long rowCount,
        String provenance,
        String description) {
}
