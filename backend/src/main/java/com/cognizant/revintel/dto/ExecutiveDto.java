package com.cognizant.revintel.dto;

import java.math.BigDecimal;
import java.util.List;

/** Serialisable shapes for the executive page: KPI tiles and what-if scenario analysis. */
public final class ExecutiveDto {

    private ExecutiveDto() {
    }

    /**
     * One headline number.
     *
     * @param unit  {@code usd} | {@code ratio} | {@code percent} | {@code count} | {@code hours}
     * @param value {@code null} when the metric is genuinely undefined (e.g. coverage against a
     *              target that is already met) -- the UI renders a dash, not a zero
     */
    public record Kpi(
            String key,
            String label,
            BigDecimal value,
            String unit,
            String caption) {
    }

    /** Everything a scenario is judged on. Computed from one forecast-row list, once. */
    public record WhatIfMetrics(
            BigDecimal weightedPipelineUsd,
            BigDecimal grossPipelineUsd,
            BigDecimal whitespaceWeightedUsd,
            BigDecimal closedWonToDateUsd,
            BigDecimal projectedTotalRevenueUsd,
            BigDecimal totalTargetUsd,
            BigDecimal remainingTargetUsd,
            BigDecimal blendedCoverageRatio,
            BigDecimal forecastDemandHours,
            int currentHeadcount,
            int recommendedFteHires,
            int recommendedContractors,
            BigDecimal hiringGapAsShareOfHeadcount,
            List<ForecastDto.CoverageRatioRow> coverageByQuarter) {
    }

    /**
     * Signed movement from baseline to scenario. A component is {@code null} only when the
     * underlying metric is undefined on one side or the other -- for instance coverage ratio when
     * a target override pushes every quarter into "already met".
     */
    public record WhatIfDelta(
            BigDecimal weightedPipelineUsd,
            BigDecimal projectedTotalRevenueUsd,
            BigDecimal totalTargetUsd,
            BigDecimal blendedCoverageRatio,
            BigDecimal forecastDemandHours,
            Integer recommendedFteHires,
            BigDecimal weightedPipelinePct) {
    }

    /** Baseline, scenario and the difference -- the payload behind the what-if sliders. */
    public record WhatIfScenario(
            BigDecimal winRateDelta,
            BigDecimal dealSizeDelta,
            BigDecimal targetDelta,
            WhatIfMetrics baseline,
            WhatIfMetrics scenario,
            WhatIfDelta delta,
            String interpretation) {
    }

    /** The Executive Insights payload. */
    public record ExecutiveSummary(
            String asOfDate,
            String currentQuarter,
            List<Kpi> kpis,
            List<ForecastDto.TrendPoint> trend,
            List<ForecastDto.CoverageRatioRow> coverage,
            List<CapacityDto.HiringRecommendation> topHiringNeeds,
            CapacityDto.CapacitySummary capacity,
            WhatIfScenario baselineScenario,
            NarrativeResponse narrative) {
    }
}
