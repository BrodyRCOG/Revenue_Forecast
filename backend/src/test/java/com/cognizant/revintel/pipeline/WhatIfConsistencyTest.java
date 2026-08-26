package com.cognizant.revintel.pipeline;

import com.cognizant.revintel.dto.ExecutiveDto;
import com.cognizant.revintel.model.Assumptions;
import com.cognizant.revintel.service.WhatIfService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-layer eval: what-if overrides actually reach every derived metric.
 *
 * <p>Regression test for the second half of the coverage-ratio bug. In the reference build moving
 * a slider changed the headline pipeline number but left the coverage ratio alone, because coverage
 * was computed from raw CRM probabilities rather than from the override-aware forecast rows. The
 * numbers on screen were individually plausible and collectively incoherent.
 *
 * <p>This also guards the capacity side of spec 4.1: because {@code CapacityService} is constructed
 * from the same rows, a win-rate override has to move demand hours too. If someone reintroduces a
 * second forecast computation inside the capacity path, that assertion fails.
 */
@SpringBootTest
class WhatIfConsistencyTest {

    private static final BigDecimal UP = new BigDecimal("0.25");

    @Autowired
    private WhatIfService whatIf;

    @Test
    void whatIfOverridesMoveCoverageRatio() {
        ExecutiveDto.WhatIfScenario better = whatIf.evaluate(
                new Assumptions(UP, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(better.baseline().blendedCoverageRatio())
                .as("baseline coverage must be computable for this test to mean anything")
                .isNotNull();
        assertThat(better.scenario().blendedCoverageRatio()).isNotNull();

        assertThat(better.scenario().weightedPipelineUsd())
                .as("+25% win rate must raise weighted pipeline")
                .isGreaterThan(better.baseline().weightedPipelineUsd());

        assertThat(better.scenario().blendedCoverageRatio())
                .as("+25% win rate must raise coverage -- this is the bug that shipped once")
                .isGreaterThan(better.baseline().blendedCoverageRatio());

        assertThat(better.delta().blendedCoverageRatio()).isNotNull().satisfies(
                delta -> assertThat(delta.signum()).isPositive());
    }

    @Test
    void raisingTargetsLowersCoverageWithoutTouchingPipeline() {
        ExecutiveDto.WhatIfScenario harder = whatIf.evaluate(
                new Assumptions(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.30")));

        assertThat(harder.scenario().weightedPipelineUsd())
                .as("a target override must not change the pipeline itself")
                .isEqualByComparingTo(harder.baseline().weightedPipelineUsd());

        assertThat(harder.scenario().totalTargetUsd())
                .isGreaterThan(harder.baseline().totalTargetUsd());

        assertThat(harder.scenario().blendedCoverageRatio()).isNotNull();
        assertThat(harder.scenario().blendedCoverageRatio())
                .as("a bigger target with the same pipeline must reduce coverage")
                .isLessThan(harder.baseline().blendedCoverageRatio());
    }

    @Test
    void dealSizeOverrideMovesPipelineAndCoverageTogether() {
        ExecutiveDto.WhatIfScenario bigger = whatIf.evaluate(
                new Assumptions(BigDecimal.ZERO, new BigDecimal("0.20"), BigDecimal.ZERO));

        assertThat(bigger.scenario().grossPipelineUsd())
                .isGreaterThan(bigger.baseline().grossPipelineUsd());
        assertThat(bigger.scenario().weightedPipelineUsd())
                .isGreaterThan(bigger.baseline().weightedPipelineUsd());
        assertThat(bigger.scenario().blendedCoverageRatio())
                .isGreaterThan(bigger.baseline().blendedCoverageRatio());
    }

    /**
     * The spec-4.1 guarantee: capacity is a transform of the <em>same</em> forecast rows, so an
     * override cannot reach the revenue numbers and miss the delivery numbers.
     */
    @Test
    void overridesReachCapacityDemandAsWellAsRevenue() {
        ExecutiveDto.WhatIfScenario better = whatIf.evaluate(
                new Assumptions(UP, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(better.scenario().forecastDemandHours())
                .as("+25% win rate raises weighted revenue, so it must raise demand hours too")
                .isGreaterThan(better.baseline().forecastDemandHours());

        assertThat(better.delta().forecastDemandHours().signum()).isPositive();
    }

    @Test
    void baselineScenarioReportsNoMovement() {
        ExecutiveDto.WhatIfScenario baseline = whatIf.evaluate(Assumptions.baseline());

        assertThat(baseline.delta().weightedPipelineUsd().signum()).isZero();
        assertThat(baseline.delta().forecastDemandHours().signum()).isZero();
        assertThat(baseline.delta().recommendedFteHires()).isZero();
        assertThat(baseline.interpretation()).contains("Baseline");
    }

    @Test
    void extremeSliderValuesAreClampedRatherThanBreakingTheForecast() {
        ExecutiveDto.WhatIfScenario extreme = whatIf.evaluate(
                Assumptions.of(50.0, 50.0, -50.0));

        assertThat(extreme.winRateDelta().doubleValue()).isLessThanOrEqualTo(1.0);
        assertThat(extreme.targetDelta().doubleValue()).isGreaterThanOrEqualTo(-1.0);
        assertThat(extreme.scenario().weightedPipelineUsd().signum()).isPositive();
        // A -100% target override leaves nothing to cover, so coverage is undefined, not enormous.
        if (extreme.scenario().blendedCoverageRatio() != null) {
            assertThat(extreme.scenario().blendedCoverageRatio().doubleValue()).isLessThan(1000.0);
        }
    }
}
