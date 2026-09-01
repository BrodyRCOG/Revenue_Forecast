package com.cognizant.revintel.dto;

import com.cognizant.revintel.model.Signal;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the Manager Overview tab renders, in one response.
 *
 * <p>The quick-look tab for a business manager: which client contracts are ending inside the
 * three-month renewal window, and where the largest revenue-increase opportunities sit. Nothing here
 * is computed by this DTO -- every figure comes from a service and is carried through unchanged.
 */
public record ManagerOverviewPayload(
        String asOfDate,
        String currentQuarter,
        String datasetSource,
        List<ExecutiveDto.Kpi> kpis,
        List<RenewalRow> renewalWindow,
        List<Signal> topOpportunities) {

    /**
     * One contract inside the renewal window.
     *
     * @param monthsToRenewal months from the as-of date until the term ends; never NaN/Infinity --
     *                        the service routes it through {@code DeliveryEconomics.rate}
     * @param withinThreeMonths the standing renewal-flag rule, computed in code rather than eyeballed
     *                          (spec section 5, rule 7)
     */
    public record RenewalRow(
            String contractId,
            String clientId,
            String clientName,
            String segment,
            String serviceType,
            BigDecimal arrUsd,
            String endDate,
            BigDecimal monthsToRenewal,
            String renewalRisk,
            boolean autoRenew,
            Integer npsScore,
            boolean withinThreeMonths) {
    }
}
