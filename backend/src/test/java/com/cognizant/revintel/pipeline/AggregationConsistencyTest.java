package com.cognizant.revintel.pipeline;

import com.cognizant.revintel.dto.ForecastDto;
import com.cognizant.revintel.model.Assumptions;
import com.cognizant.revintel.model.ForecastRow;
import com.cognizant.revintel.service.ForecastingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-layer eval: internal consistency.
 *
 * <p>Every aggregation is a fold over the same forecast-row list, so they must all add up to the
 * same total. A regression here means a grouping key is dropping or double-counting rows -- the
 * kind of bug that shows up as a chart that disagrees with the KPI tile above it.
 */
@SpringBootTest
class AggregationConsistencyTest {

    private static final BigDecimal PENNY = new BigDecimal("0.05");

    @Autowired
    private ForecastingService forecasting;

    private List<ForecastRow> rows;
    private ForecastDto.ForecastTotals totals;

    @BeforeEach
    void computeOnce() {
        rows = forecasting.forecastRows(Assumptions.baseline());
        totals = forecasting.totals(rows);
    }

    @Test
    void forecastRowsExistForBothSources() {
        assertThat(rows).isNotEmpty();
        assertThat(rows).anyMatch(row -> !row.isWhitespace());
        assertThat(rows).as("the whole point of the tool is whitespace the CRM has not seen")
                .anyMatch(ForecastRow::isWhitespace);
    }

    @Test
    void everyDimensionSumsToTheSameWeightedTotal() {
        for (String dimension : List.of("quarter", "segment", "practice", "opportunityType", "client", "source")) {
            BigDecimal summed = forecasting.forecastBy(dimension, rows).stream()
                    .map(ForecastDto.ForecastBucket::weightedAmountUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(summed)
                    .as("forecastBy(%s) must sum to the weighted pipeline total", dimension)
                    .isCloseTo(totals.weightedPipelineUsd(), org.assertj.core.data.Offset.offset(PENNY));
        }
    }

    @Test
    void heatmapSumsToTheSameWeightedTotal() {
        BigDecimal summed = forecasting.opportunityHeatmap(rows).stream()
                .map(ForecastDto.HeatmapCell::weightedAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summed).isCloseTo(totals.weightedPipelineUsd(), org.assertj.core.data.Offset.offset(PENNY));
    }

    @Test
    void bucketCountsAccountForEveryRow() {
        int counted = forecasting.forecastBy("quarter", rows).stream()
                .mapToInt(ForecastDto.ForecastBucket::rowCount).sum();
        assertThat(counted).isEqualTo(rows.size());
        assertThat(totals.openOpportunityCount() + totals.whitespaceSignalCount()).isEqualTo(rows.size());
    }

    @Test
    void weightedNeverExceedsGross() {
        assertThat(totals.weightedPipelineUsd()).isLessThanOrEqualTo(totals.grossPipelineUsd());
        for (ForecastRow row : rows) {
            assertThat(row.weightedAmountUsd())
                    .as("row %s weighted above gross", row.referenceId())
                    .isLessThanOrEqualTo(row.amountUsd());
        }
    }

    @Test
    void coverageRowsReconcileTargetAgainstWonAndRemaining() {
        for (ForecastDto.CoverageRatioRow row : forecasting.pipelineCoverageRatio(rows, Assumptions.baseline())) {
            assertThat(row.remainingTargetUsd())
                    .as("quarter %s: target - won must equal remaining", row.quarter())
                    .isCloseTo(row.targetUsd().subtract(row.closedWonUsd()),
                            org.assertj.core.data.Offset.offset(PENNY));

            if (row.coverageRatio() != null) {
                BigDecimal implied = row.weightedPipelineUsd()
                        .divide(row.remainingTargetUsd(), 4, RoundingMode.HALF_UP);
                assertThat(row.coverageRatio()).isEqualTo(implied);
            }
        }
    }

    /**
     * The blended coverage ratio must be exactly the forward-quarter fold, and must ignore closed
     * quarters. A quarter that has already ended cannot be covered by future pipeline, and letting
     * its shortfall into the denominator made the headline number quietly pessimistic.
     */
    @Test
    void blendedCoverageIsTheForwardQuarterFold() {
        List<ForecastDto.CoverageRatioRow> coverage =
                forecasting.pipelineCoverageRatio(rows, Assumptions.baseline());

        BigDecimal pipeline = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        for (ForecastDto.CoverageRatioRow row : coverage) {
            if (row.targetAlreadyMet() || "actual".equals(row.phase())) {
                continue;
            }
            pipeline = pipeline.add(row.weightedPipelineUsd());
            remaining = remaining.add(row.remainingTargetUsd());
        }

        assertThat(remaining.signum())
                .as("the dataset must leave something to cover for this test to mean anything")
                .isPositive();
        assertThat(forecasting.blendedCoverageRatio(rows, Assumptions.baseline()))
                .isEqualByComparingTo(pipeline.divide(remaining, 4, RoundingMode.HALF_UP));

        // A closed quarter that missed its target exists in this dataset; confirm it is excluded
        // rather than silently absent.
        assertThat(coverage)
                .as("expected at least one closed quarter that missed target")
                .anyMatch(row -> "actual".equals(row.phase()) && !row.targetAlreadyMet());
    }

    @Test
    void trendCumulativesAreMonotonic() {
        BigDecimal previousActual = BigDecimal.valueOf(-1);
        BigDecimal previousProjected = BigDecimal.valueOf(-1);
        for (ForecastDto.TrendPoint point : forecasting.trendLines(rows, Assumptions.baseline())) {
            assertThat(point.cumulativeActualUsd()).isGreaterThanOrEqualTo(previousActual);
            assertThat(point.cumulativeProjectedUsd()).isGreaterThanOrEqualTo(previousProjected);
            previousActual = point.cumulativeActualUsd();
            previousProjected = point.cumulativeProjectedUsd();
        }
    }

    @Test
    void winRatesAreSmoothedTowardsTheParentRate() {
        List<ForecastDto.WinRateRow> winRates = forecasting.winRateRows();
        assertThat(winRates).isNotEmpty();

        for (ForecastDto.WinRateRow row : winRates) {
            assertThat(row.smoothedWinRate().doubleValue()).isBetween(0.01, 0.99);
            if (row.rawWinRate() != null && row.closedCount() <= 3) {
                // A thin cell must not simply echo its raw rate -- that is what smoothing is for.
                boolean rawIsExtreme = row.rawWinRate().doubleValue() <= 0.01
                        || row.rawWinRate().doubleValue() >= 0.99;
                if (rawIsExtreme) {
                    assertThat(row.smoothedWinRate()).isNotEqualByComparingTo(row.rawWinRate());
                }
            }
        }
    }
}
