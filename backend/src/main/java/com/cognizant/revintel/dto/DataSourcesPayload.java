package com.cognizant.revintel.dto;

import com.cognizant.revintel.repository.projection.DataCatalogRow;

import java.util.List;

/**
 * The Data Sources tab: what data exists, where it came from, and how much of it is real.
 *
 * @param datasetSource   {@code generated} when the Python pipeline has been run,
 *                        {@code fallback} when the app is on the minimal hand-written seed
 * @param realAnchorCount OEM models with a genuine vendor-sourced lifecycle date, as opposed to a
 *                        procedural variant -- the honest headline about how much of this POC is
 *                        anchored to reality
 */
public record DataSourcesPayload(
        String asOfDate,
        String datasetSource,
        long totalRows,
        int tableCount,
        long realAnchorCount,
        long oemModelCount,
        List<DataCatalogRow> tables,
        List<RealAnchor> realAnchors,
        String provenanceNote) {

    /** One genuinely sourced OEM lifecycle record. */
    public record RealAnchor(
            String id,
            String oem,
            String modelName,
            String category,
            String endOfSale,
            String endOfSupport,
            String sourceConfidence,
            String sourceUrl) {
    }
}
