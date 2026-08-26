package com.cognizant.revintel.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Serialisable shapes for everything {@code ForecastingService} aggregates.
 *
 * <p>Every monetary field is a {@link BigDecimal} that is either a real value or {@code null} --
 * never {@code NaN} or {@code Infinity}, which Jackson cannot represent in valid JSON. Ratios that
 * are genuinely undefined (a target already met, a variance against zero actuals) are {@code null}
 * with a companion boolean explaining why, rather than a sentinel number the UI would render as
 * fact. {@code JsonPayloadSafetyTest} enforces this.
 */
public final class ForecastDto {

    private ForecastDto() {
    }

    /** One bucket of a {@code forecastBy(dimension)} aggregation. */
    public record ForecastBucket(
            String key,
            String label,
            BigDecimal weightedAmountUsd,
            BigDecimal grossAmountUsd,
            BigDecimal whitespaceWeightedUsd,
            int rowCount) {
    }

    /** One cell of the segment x opportunity-type heatmap. */
    public record HeatmapCell(
            String segment,
            String opportunityType,
            BigDecimal weightedAmountUsd,
            BigDecimal grossAmountUsd,
            BigDecimal whitespaceWeightedUsd,
            int rowCount) {
    }

    /**
     * @param actualUsd    closed-won revenue; {@code null} for quarters that have not started
     * @param projectedUsd weighted forecast; {@code null} for quarters already closed out --
     *                     this POC recomputes forecasts live and keeps no snapshots, so there is
     *                     no honest "what we predicted back then" number for a past quarter
     * @param variancePct  {@code null} when there is no actual to compare against
     */
    public record ActualVsProjectedRow(
            String quarter,
            String phase,
            BigDecimal actualUsd,
            BigDecimal projectedUsd,
            BigDecimal varianceUsd,
            BigDecimal variancePct) {
    }

    /**
     * Pipeline coverage for one quarter.
     *
     * @param coverageRatio  weighted pipeline / remaining target, or {@code null} when
     *                       {@code targetAlreadyMet} -- dividing by a near-zero remaining target
     *                       is what produced the 82,427x coverage ratio in the reference build
     * @param targetAlreadyMet closed-won revenue has already reached the target
     */
    public record CoverageRatioRow(
            String quarter,
            String phase,
            BigDecimal targetUsd,
            BigDecimal closedWonUsd,
            BigDecimal remainingTargetUsd,
            BigDecimal weightedPipelineUsd,
            BigDecimal coverageRatio,
            boolean targetAlreadyMet) {
    }

    /** One point on the actual / projected / target trend chart. */
    public record TrendPoint(
            String quarter,
            String phase,
            BigDecimal actualUsd,
            BigDecimal projectedUsd,
            BigDecimal targetUsd,
            BigDecimal cumulativeActualUsd,
            BigDecimal cumulativeProjectedUsd) {
    }

    /** A smoothed win rate, with the evidence behind it. */
    public record WinRateRow(
            String segment,
            String opportunityType,
            BigDecimal smoothedWinRate,
            BigDecimal rawWinRate,
            int closedCount,
            int wonCount) {
    }

    /** Roll-up of a whole forecast-row list -- the KPI tiles at the top of the revenue page. */
    public record ForecastTotals(
            BigDecimal weightedPipelineUsd,
            BigDecimal grossPipelineUsd,
            BigDecimal pipelineOnlyWeightedUsd,
            BigDecimal whitespaceWeightedUsd,
            BigDecimal closedWonToDateUsd,
            BigDecimal blendedWinRate,
            int openOpportunityCount,
            int whitespaceSignalCount,
            List<String> forecastQuarters) {
    }
}
