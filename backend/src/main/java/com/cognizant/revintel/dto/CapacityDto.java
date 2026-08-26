package com.cognizant.revintel.dto;

import java.math.BigDecimal;
import java.util.List;

/** Serialisable shapes for everything {@code CapacityService} computes. */
public final class CapacityDto {

    private CapacityDto() {
    }

    /** Forecast-implied delivery hours for one resource group in one quarter. */
    public record SkillDemandRow(
            String resourceGroup,
            String practice,
            String quarter,
            BigDecimal demandHours,
            BigDecimal demandFte,
            BigDecimal pipelineDemandHours,
            BigDecimal whitespaceDemandHours) {
    }

    /**
     * What one resource group can sustainably deliver in a quarter.
     *
     * @param sustainableHours capacity hours times target utilisation -- the ceiling you can run
     *                         at indefinitely, not the theoretical maximum
     */
    public record CapacityRow(
            String resourceGroup,
            String practice,
            int headcount,
            int contractorHeadcount,
            BigDecimal quarterlyCapacityHours,
            BigDecimal sustainableHours,
            BigDecimal sustainableRevenueUsd,
            BigDecimal currentUtilization,
            BigDecimal targetUtilization,
            BigDecimal avgBillRateUsd) {
    }

    /**
     * @param status {@code Under-utilised} | {@code Healthy} | {@code Stretched} | {@code Over capacity}
     */
    public record UtilizationProjectionRow(
            String resourceGroup,
            String practice,
            String quarter,
            BigDecimal demandHours,
            BigDecimal capacityHours,
            BigDecimal projectedUtilization,
            String status) {
    }

    /**
     * Hiring advice for one resource group.
     *
     * <p>FTE hires are sized off <em>average</em> forecast-quarter demand and contractors off the
     * peak above that average. Sizing permanent headcount off the peak is how you end up
     * recommending a 5x bench for one busy quarter -- the failure mode
     * {@code HiringGapPlausibilityTest} guards against.
     *
     * @param gapAsShareOfHeadcount recommended hires as a fraction of current headcount; the
     *                              plausibility test asserts this stays modest
     */
    public record HiringRecommendation(
            String resourceGroup,
            String practice,
            int currentHeadcount,
            int currentContractors,
            BigDecimal avgQuarterlyDemandHours,
            BigDecimal peakQuarterlyDemandHours,
            BigDecimal sustainableHours,
            BigDecimal averageGapHours,
            BigDecimal peakGapHours,
            int recommendedFteHires,
            int recommendedContractors,
            BigDecimal gapAsShareOfHeadcount,
            String urgency,
            String rationale) {
    }

    /** Fleet-level roll-up for the capacity KPI tiles. */
    public record CapacitySummary(
            int totalHeadcount,
            int totalContractors,
            BigDecimal totalForecastDemandHours,
            BigDecimal totalSustainableHours,
            BigDecimal fleetProjectedUtilization,
            int recommendedFteHires,
            int recommendedContractors,
            BigDecimal hiringGapAsShareOfHeadcount,
            int groupsOverCapacity,
            List<String> forecastQuarters) {
    }
}
