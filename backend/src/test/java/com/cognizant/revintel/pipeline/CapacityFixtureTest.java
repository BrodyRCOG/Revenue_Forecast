package com.cognizant.revintel.pipeline;

import com.cognizant.revintel.dto.CapacityDto;
import com.cognizant.revintel.entity.Workforce;
import com.cognizant.revintel.model.ForecastRow;
import com.cognizant.revintel.service.CapacityService;
import com.cognizant.revintel.service.DeliveryEconomics;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-layer eval: {@code CapacityService} is exercisable from fixtures alone.
 *
 * <p>No {@code @SpringBootTest}, no database, no {@code ForecastingService}. That is the concrete
 * payoff of taking forecast rows as a constructor argument (spec 4.1) -- if someone changes
 * {@code CapacityService} to fetch its own data, this test stops compiling, which is exactly the
 * feedback you want.
 */
class CapacityFixtureTest {

    private static final String GROUP = "Network Engineering";

    @Test
    void demandHoursAreADeterministicTransformOfWeightedRevenue() {
        // One Infrastructure Refresh row: 35% labour content at $175/hour.
        List<ForecastRow> rows = List.of(row("Infrastructure Refresh", "2026-Q4", 1_000_000));
        CapacityService capacity = new CapacityService(rows, List.of(workforce(GROUP, 20)));

        double expectedTotalHours = 1_000_000 * 0.35 / 175.0;   // 2000 hours
        double expectedNetworkHours = expectedTotalHours * 0.45; // 900 hours

        List<CapacityDto.SkillDemandRow> demand = capacity.skillDemandForecast();
        assertThat(demand).extracting(CapacityDto.SkillDemandRow::resourceGroup)
                .containsExactlyInAnyOrderElementsOf(
                        DeliveryEconomics.RESOURCE_MIX.get("Infrastructure Refresh").keySet());

        CapacityDto.SkillDemandRow network = demand.stream()
                .filter(r -> GROUP.equals(r.resourceGroup()))
                .findFirst().orElseThrow();
        assertThat(network.demandHours().doubleValue()).isCloseTo(expectedNetworkHours, within(0.01));
        assertThat(network.quarter()).isEqualTo("2026-Q4");

        double summed = demand.stream().mapToDouble(r -> r.demandHours().doubleValue()).sum();
        assertThat(summed).as("the mix must account for all the hours")
                .isCloseTo(expectedTotalHours, within(0.01));
    }

    @Test
    void hardwareHeavyDealsGenerateFewerHoursPerDollarThanSoftwareOnes() {
        double refreshHours = DeliveryEconomics.demandHours("Infrastructure Refresh", BigDecimal.valueOf(1_000_000));
        double modernizationHours = DeliveryEconomics.demandHours("Modernization", BigDecimal.valueOf(1_000_000));
        assertThat(refreshHours).isLessThan(modernizationHours);
    }

    @Test
    void everyResourceMixRowSumsToOne() {
        DeliveryEconomics.RESOURCE_MIX.forEach((type, mix) -> {
            double total = mix.values().stream().mapToDouble(Double::doubleValue).sum();
            assertThat(total).as("mix for %s", type).isCloseTo(1.0, within(1e-9));
        });
    }

    @Test
    void anAdequatelyStaffedGroupIsToldToHireNobody() {
        // 900 hours per quarter of demand; 20 FTE at 430 h/quarter * 0.85 = 7310 sustainable hours.
        CapacityService capacity = new CapacityService(
                List.of(row("Infrastructure Refresh", "2026-Q4", 1_000_000)),
                List.of(workforce(GROUP, 20)));

        CapacityDto.HiringRecommendation recommendation = capacity.hiringRecommendations().stream()
                .filter(r -> GROUP.equals(r.resourceGroup()))
                .findFirst().orElseThrow();

        assertThat(recommendation.recommendedFteHires()).isZero();
        assertThat(recommendation.recommendedContractors()).isZero();
        assertThat(recommendation.urgency()).isEqualTo("None");
        assertThat(recommendation.rationale()).contains("No hiring indicated");
    }

    @Test
    void hiresAreSizedOffAverageDemandAndContractorsOffThePeak() {
        // Three quarters: two quiet, one that spikes 10x. Permanent hiring must follow the average,
        // contractors the spike -- sizing FTE off the peak is the "hire 5x your bench" bug.
        List<ForecastRow> rows = List.of(
                row("Infrastructure Refresh", "2026-Q4", 1_000_000),
                row("Infrastructure Refresh", "2027-Q1", 1_000_000),
                row("Infrastructure Refresh", "2027-Q2", 20_000_000));

        CapacityService capacity = new CapacityService(rows, List.of(workforce(GROUP, 6)));
        CapacityDto.HiringRecommendation recommendation = capacity.hiringRecommendations().stream()
                .filter(r -> GROUP.equals(r.resourceGroup()))
                .findFirst().orElseThrow();

        assertThat(recommendation.peakQuarterlyDemandHours())
                .isGreaterThan(recommendation.avgQuarterlyDemandHours());
        assertThat(recommendation.recommendedContractors())
                .as("the spike belongs to contractors, not to permanent headcount")
                .isGreaterThan(recommendation.recommendedFteHires());
    }

    @Test
    void emptyInputsProduceEmptyOutputRatherThanDividingByZero() {
        CapacityService capacity = new CapacityService(List.of(), List.of());

        assertThat(capacity.skillDemandForecast()).isEmpty();
        assertThat(capacity.hiringRecommendations()).isEmpty();
        assertThat(capacity.utilizationProjection()).isEmpty();

        CapacityDto.CapacitySummary summary = capacity.summary();
        assertThat(summary.totalHeadcount()).isZero();
        assertThat(summary.recommendedFteHires()).isZero();
        // No workforce means no denominator: null, not NaN, not zero.
        assertThat(summary.fleetProjectedUtilization()).isNull();
        assertThat(summary.hiringGapAsShareOfHeadcount()).isNull();
    }

    @Test
    void nullConstructorArgumentsAreTreatedAsEmpty() {
        CapacityService capacity = new CapacityService(null, null);
        assertThat(capacity.skillDemandForecast()).isEmpty();
        assertThat(capacity.summary().totalHeadcount()).isZero();
    }

    // -- fixtures ----------------------------------------------------------------------

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    private static ForecastRow row(String opportunityType, String quarter, double weightedAmount) {
        return new ForecastRow(
                ForecastRow.SOURCE_PIPELINE, "FIXTURE-1", "CLI-0001", "Fixture Bank",
                "Community Bank", DeliveryEconomics.practiceFor(opportunityType),
                opportunityType, quarter,
                BigDecimal.valueOf(weightedAmount), BigDecimal.ONE,
                BigDecimal.valueOf(weightedAmount));
    }

    /**
     * {@link Workforce} is a JPA entity with a protected no-arg constructor and no setters, by
     * design -- nothing in the application writes to it. Reflection here keeps the entity read-only
     * for production code while still allowing a fixture.
     */
    private static Workforce workforce(String resourceGroup, int headcount) {
        try {
            Workforce entity = newInstance();
            set(entity, "id", "WF-FIXTURE");
            set(entity, "resourceGroup", resourceGroup);
            set(entity, "practice", "Infrastructure");
            set(entity, "region", "National");
            set(entity, "headcount", headcount);
            set(entity, "contractorHeadcount", 0);
            set(entity, "avgBillRateUsd", BigDecimal.valueOf(185));
            set(entity, "targetUtilization", BigDecimal.valueOf(0.85));
            set(entity, "currentUtilization", BigDecimal.valueOf(0.80));
            set(entity, "annualCapacityHoursPerFte", BigDecimal.valueOf(1720));
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Workforce fixture construction failed", e);
        }
    }

    private static Workforce newInstance() throws ReflectiveOperationException {
        var constructor = Workforce.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void set(Workforce entity, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = Workforce.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(entity, value);
    }
}
