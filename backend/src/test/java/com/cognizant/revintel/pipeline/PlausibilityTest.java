package com.cognizant.revintel.pipeline;

import com.cognizant.revintel.dto.CapacityDto;
import com.cognizant.revintel.dto.ForecastDto;
import com.cognizant.revintel.entity.Workforce;
import com.cognizant.revintel.model.Assumptions;
import com.cognizant.revintel.model.ForecastRow;
import com.cognizant.revintel.repository.WorkforceRepository;
import com.cognizant.revintel.service.CapacityService;
import com.cognizant.revintel.service.ForecastingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-layer eval: plausibility.
 *
 * <p>These are regression tests for two real bugs found building the reference version of this
 * app. Neither was a crash -- both produced confident, well-formatted, obviously-wrong numbers,
 * which is the failure mode a POC on synthetic data is most likely to ship.
 */
@SpringBootTest
class PlausibilityTest {

    /**
     * Ceiling on forecast demand relative to what a group actually delivered historically.
     *
     * <p>This is the assertion that would have caught the original bug. The generator's growth
     * assumption is 1.10x, so anything approaching 1.6x means the forecast's dollars-to-hours
     * transform and the demand model the workforce was sized against have stopped agreeing. The
     * bug produced ratios of 5-6x.
     */
    private static final double MAX_DEMAND_GROWTH = 1.60;

    /**
     * Ceiling on per-group hiring, as a share of current headcount.
     *
     * <p>Derived, not picked: a group whose staffing factor landed at the bottom of the generator's
     * mandated 0.82-1.15 jitter is already carrying 1/0.82 = 1.22x its sustainable load, and the
     * dataset's growth assumption is another 1.10x. So 1.22 x 1.10 = 1.34, plus room for
     * whitespace and resource-mix drift. A single unlucky group legitimately reaching ~50% is a
     * finding the tool should surface; 500% is a bug.
     */
    private static final double MAX_GROUP_HIRING_SHARE = 0.55;

    /** Fleet-wide, the staffing jitter averages out, so the bar is tighter. */
    private static final double MAX_FLEET_HIRING_SHARE = 0.40;

    /** Coverage above this is not a forecast, it is a divide-by-almost-zero. */
    private static final double MAX_COVERAGE_RATIO = 10.0;

    @Autowired
    private ForecastingService forecasting;

    @Autowired
    private WorkforceRepository workforce;

    /**
     * Regression test for "hiring recommendations implying 5-6x current headcount".
     *
     * <p>Root cause in the reference build: workforce size and opportunity revenue scale were
     * generated independently, so forecast-implied demand had no relationship to the bench it was
     * compared against. Fix: the generator sizes headcount off
     * {@code historical_quarterly_demand_hours()}, the same demand model this transform reads.
     */
    @Test
    void hiringGapPlausibility() {
        List<ForecastRow> rows = forecasting.forecastRows(Assumptions.baseline());
        List<Workforce> workforceRows = workforce.findAll();
        CapacityService capacity = new CapacityService(rows, workforceRows);

        assertThat(workforceRows).isNotEmpty();
        Map<String, Workforce> byGroup = workforceRows.stream()
                .collect(java.util.stream.Collectors.toMap(Workforce::getResourceGroup, w -> w));

        for (CapacityDto.HiringRecommendation row : capacity.hiringRecommendations()) {
            Workforce group = byGroup.get(row.resourceGroup());
            assertThat(group).as("workforce row for %s", row.resourceGroup()).isNotNull();

            // The generator recorded this group's historical demand implicitly: current utilisation
            // IS delivered hours over raw capacity. Recovering it lets this assertion separate
            // "demand and the demand model disagree" (the bug) from "this group happens to be
            // understaffed and growing" (a real finding).
            double rawQuarterlyCapacity =
                    group.getHeadcount() * group.getAnnualCapacityHoursPerFte().doubleValue() / 4.0;
            double historicalDemand = group.getCurrentUtilization().doubleValue() * rawQuarterlyCapacity;
            double forecastDemand = row.avgQuarterlyDemandHours().doubleValue();

            assertThat(forecastDemand / historicalDemand)
                    .as("%s: forecast demand %.0f h/qtr against %.0f h/qtr actually delivered "
                                    + "-- the dollars-to-hours transform and the demand model the "
                                    + "workforce was sized against have drifted apart",
                            row.resourceGroup(), forecastDemand, historicalDemand)
                    .isLessThanOrEqualTo(MAX_DEMAND_GROWTH);

            // Floor of 2 heads so a small group is not flagged for a rounding artefact.
            int allowed = Math.max(2, (int) Math.ceil(row.currentHeadcount() * MAX_GROUP_HIRING_SHARE));
            assertThat(row.recommendedFteHires())
                    .as("%s: %d hires against %d FTE (%.0f avg demand hours vs %.0f sustainable)",
                            row.resourceGroup(), row.recommendedFteHires(), row.currentHeadcount(),
                            forecastDemand, row.sustainableHours().doubleValue())
                    .isLessThanOrEqualTo(allowed);
        }

        CapacityDto.CapacitySummary summary = capacity.summary();
        assertThat(summary.hiringGapAsShareOfHeadcount())
                .as("fleet-wide hiring gap")
                .isNotNull();
        assertThat(summary.hiringGapAsShareOfHeadcount().doubleValue())
                .as("fleet-wide, %d hires against %d FTE",
                        summary.recommendedFteHires(), summary.totalHeadcount())
                .isLessThanOrEqualTo(MAX_FLEET_HIRING_SHARE);
    }

    /**
     * Regression test for "coverage ratio showing 82,427x".
     *
     * <p>Root cause: dividing weighted pipeline by a remaining target that had rounded to almost
     * nothing. Fix: {@code targetAlreadyMet} short-circuits the division and the ratio comes back
     * {@code null}, which the UI renders as "target met" rather than as an enormous multiple.
     */
    @Test
    void coverageRatioNoExtremeValues() {
        List<ForecastRow> rows = forecasting.forecastRows(Assumptions.baseline());
        List<ForecastDto.CoverageRatioRow> coverage =
                forecasting.pipelineCoverageRatio(rows, Assumptions.baseline());

        assertThat(coverage).isNotEmpty();
        assertThat(coverage).as("at least one quarter must have a computable coverage ratio")
                .anyMatch(row -> row.coverageRatio() != null);

        for (ForecastDto.CoverageRatioRow row : coverage) {
            if (row.targetAlreadyMet()) {
                assertThat(row.coverageRatio())
                        .as("quarter %s: an already-met target must yield null, never a number",
                                row.quarter())
                        .isNull();
                continue;
            }
            assertThat(row.coverageRatio()).as("quarter %s", row.quarter()).isNotNull();
            assertThat(row.coverageRatio().doubleValue())
                    .as("quarter %s coverage: pipeline %s over remaining target %s",
                            row.quarter(), row.weightedPipelineUsd(), row.remainingTargetUsd())
                    .isBetween(0.0, MAX_COVERAGE_RATIO);
        }

        java.math.BigDecimal blended = forecasting.blendedCoverageRatio(rows, Assumptions.baseline());
        if (blended != null) {
            assertThat(blended.doubleValue()).isBetween(0.0, MAX_COVERAGE_RATIO);
        }
    }

    @Test
    void utilizationProjectionStaysInARealisticBand() {
        List<ForecastRow> rows = forecasting.forecastRows(Assumptions.baseline());
        CapacityService capacity = new CapacityService(rows, workforce.findAll());

        List<CapacityDto.UtilizationProjectionRow> projection = capacity.utilizationProjection();
        assertThat(projection).isNotEmpty();

        for (CapacityDto.UtilizationProjectionRow row : projection) {
            assertThat(row.projectedUtilization().doubleValue())
                    .as("%s %s projected utilisation", row.resourceGroup(), row.quarter())
                    .isBetween(0.0, 2.0);
        }
    }

    @Test
    void whitespaceIsAMinorityOfTheForecast() {
        // Whitespace is speculative by definition. If it dominates the forecast, the conversion
        // assumption is wrong and the headline number is fantasy rather than pipeline.
        ForecastDto.ForecastTotals totals =
                forecasting.totals(forecasting.forecastRows(Assumptions.baseline()));
        double whitespaceShare = totals.whitespaceWeightedUsd().doubleValue()
                / Math.max(1.0, totals.weightedPipelineUsd().doubleValue());
        assertThat(whitespaceShare)
                .as("whitespace share of weighted forecast")
                .isLessThan(0.60);
    }
}
