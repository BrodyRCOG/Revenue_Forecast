package com.cognizant.revintel.service;

import com.cognizant.revintel.dto.CapacityDto;
import com.cognizant.revintel.dto.ExecutiveDto;
import com.cognizant.revintel.dto.ForecastDto;
import com.cognizant.revintel.entity.Workforce;
import com.cognizant.revintel.model.Assumptions;
import com.cognizant.revintel.model.ForecastRow;
import com.cognizant.revintel.repository.WorkforceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Executive scenario analysis.
 *
 * <p>A scenario is not a separate model. It re-runs the <em>same</em> {@link ForecastingService}
 * and {@link CapacityService} with adjusted assumptions and diffs the result against baseline. That
 * matters for a specific reason: in the reference build the what-if sliders moved the headline
 * pipeline number but left the coverage ratio untouched, because coverage was computed from raw CRM
 * probabilities rather than from the forecast rows the overrides actually reached. Here every
 * number on both sides is folded out of one row list, so a slider cannot move one and miss another.
 *
 * <p>{@code WhatIfOverridesMoveCoverageRatioTest} is the regression test for that.
 */
@Service
public class WhatIfService {

    private final ForecastingService forecasting;
    private final WorkforceRepository workforce;

    public WhatIfService(ForecastingService forecasting, WorkforceRepository workforce) {
        this.forecasting = forecasting;
        this.workforce = workforce;
    }

    /** Baseline, scenario, and the delta between them. */
    public ExecutiveDto.WhatIfScenario evaluate(Assumptions assumptions) {
        List<Workforce> workforceRows = workforce.findAll();

        ExecutiveDto.WhatIfMetrics baseline = metrics(Assumptions.baseline(), workforceRows);
        ExecutiveDto.WhatIfMetrics scenario = assumptions.isBaseline()
                ? baseline
                : metrics(assumptions, workforceRows);

        return new ExecutiveDto.WhatIfScenario(
                assumptions.winRateDelta(),
                assumptions.dealSizeDelta(),
                assumptions.targetDelta(),
                baseline,
                scenario,
                delta(baseline, scenario),
                interpret(assumptions, baseline, scenario));
    }

    /**
     * All scenario metrics, computed from a single forecast-row list.
     *
     * <p>The rows are built once and handed to both the revenue aggregations and
     * {@code CapacityService}, which is what makes revenue and capacity provably describe the same
     * scenario (spec 4.1).
     */
    public ExecutiveDto.WhatIfMetrics metrics(Assumptions assumptions, List<Workforce> workforceRows) {
        List<ForecastRow> rows = forecasting.forecastRows(assumptions);
        CapacityService capacity = new CapacityService(rows, workforceRows);

        ForecastDto.ForecastTotals totals = forecasting.totals(rows);
        List<ForecastDto.CoverageRatioRow> coverage = forecasting.pipelineCoverageRatio(rows, assumptions);
        CapacityDto.CapacitySummary capacitySummary = capacity.summary();

        // Forward quarters only, matching blendedCoverageRatio. "Total target" on an executive
        // page means what is still to be sold, not the sum of every target in the dataset's
        // history.
        BigDecimal totalTarget = coverage.stream()
                .filter(ForecastingService::isForwardLooking)
                .map(ForecastDto.CoverageRatioRow::targetUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainingTarget = coverage.stream()
                .filter(ForecastingService::isForwardLooking)
                .filter(row -> !row.targetAlreadyMet())
                .map(ForecastDto.CoverageRatioRow::remainingTargetUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ExecutiveDto.WhatIfMetrics(
                totals.weightedPipelineUsd(),
                totals.grossPipelineUsd(),
                totals.whitespaceWeightedUsd(),
                totals.closedWonToDateUsd(),
                totals.closedWonToDateUsd().add(totals.weightedPipelineUsd()),
                totalTarget,
                remainingTarget,
                forecasting.blendedCoverageRatio(rows, assumptions),
                capacitySummary.totalForecastDemandHours(),
                capacitySummary.totalHeadcount(),
                capacitySummary.recommendedFteHires(),
                capacitySummary.recommendedContractors(),
                capacitySummary.hiringGapAsShareOfHeadcount(),
                coverage);
    }

    private static ExecutiveDto.WhatIfDelta delta(ExecutiveDto.WhatIfMetrics baseline,
                                                 ExecutiveDto.WhatIfMetrics scenario) {
        BigDecimal pipelineDelta = scenario.weightedPipelineUsd().subtract(baseline.weightedPipelineUsd());
        BigDecimal pipelinePct = baseline.weightedPipelineUsd().signum() == 0
                ? null
                : pipelineDelta.divide(baseline.weightedPipelineUsd(), 4, RoundingMode.HALF_UP);

        return new ExecutiveDto.WhatIfDelta(
                pipelineDelta,
                scenario.projectedTotalRevenueUsd().subtract(baseline.projectedTotalRevenueUsd()),
                scenario.totalTargetUsd().subtract(baseline.totalTargetUsd()),
                subtractNullable(scenario.blendedCoverageRatio(), baseline.blendedCoverageRatio()),
                scenario.forecastDemandHours().subtract(baseline.forecastDemandHours()),
                scenario.recommendedFteHires() - baseline.recommendedFteHires(),
                pipelinePct);
    }

    /** {@code null} when either side is undefined -- a delta between a number and nothing is not 0. */
    private static BigDecimal subtractNullable(BigDecimal left, BigDecimal right) {
        return (left == null || right == null) ? null : left.subtract(right);
    }

    private static String interpret(Assumptions assumptions,
                                    ExecutiveDto.WhatIfMetrics baseline,
                                    ExecutiveDto.WhatIfMetrics scenario) {
        if (assumptions.isBaseline()) {
            return "Baseline: current win rates, deal sizes and targets, with no overrides applied.";
        }

        StringBuilder text = new StringBuilder("Applying ");
        text.append(String.format("%+.0f%% win rate, %+.0f%% deal size and %+.0f%% target",
                assumptions.winRateDelta().doubleValue() * 100,
                assumptions.dealSizeDelta().doubleValue() * 100,
                assumptions.targetDelta().doubleValue() * 100));

        BigDecimal pipelineDelta = scenario.weightedPipelineUsd().subtract(baseline.weightedPipelineUsd());
        text.append(String.format(" moves weighted pipeline by %s",
                formatSignedUsd(pipelineDelta.doubleValue())));

        if (scenario.blendedCoverageRatio() != null && baseline.blendedCoverageRatio() != null) {
            text.append(String.format(" and coverage from %.2fx to %.2fx",
                    baseline.blendedCoverageRatio().doubleValue(),
                    scenario.blendedCoverageRatio().doubleValue()));
        } else if (scenario.blendedCoverageRatio() == null) {
            text.append(" and leaves every quarter's target already met, so coverage is undefined");
        }

        int hiringDelta = scenario.recommendedFteHires() - baseline.recommendedFteHires();
        text.append(hiringDelta == 0
                ? ". Hiring requirement is unchanged."
                : String.format(". Hiring requirement moves by %+d FTE.", hiringDelta));
        return text.toString();
    }

    private static String formatSignedUsd(double value) {
        String sign = value < 0 ? "-" : "+";
        double magnitude = Math.abs(value);
        return magnitude >= 1_000_000
                ? String.format("%s$%.1fM", sign, magnitude / 1_000_000)
                : String.format("%s$%.0fk", sign, magnitude / 1_000);
    }
}
