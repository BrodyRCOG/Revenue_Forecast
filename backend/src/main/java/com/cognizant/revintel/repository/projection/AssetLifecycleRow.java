package com.cognizant.revintel.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Flat join of {@code installed_base} x {@code oem_models} x {@code clients}. Produced by
 * {@code InstalledBaseRepository.withLifecycle()} so signal detection can reason about an asset's
 * EOL/EOS dates and its owning client in one pass, without lazy-loading associations.
 */
public record AssetLifecycleRow(
        String assetId,
        String clientId,
        String clientName,
        String segment,
        String revenueTier,
        String site,
        Integer quantity,
        String criticality,
        BigDecimal annualSupportCostUsd,
        String oemModelId,
        String oem,
        String modelName,
        String category,
        LocalDate endOfSale,
        LocalDate endOfSupport,
        boolean realAnchor,
        String sourceUrl,
        String sourceConfidence) {
}
