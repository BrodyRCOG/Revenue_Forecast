package com.cognizant.revintel.pipeline;

import com.cognizant.revintel.dto.ExecutiveDto;
import com.cognizant.revintel.dto.ManagerOverviewPayload;
import com.cognizant.revintel.model.Signal;
import com.cognizant.revintel.service.RevenueIntelligenceOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-layer eval: Manager Overview.
 *
 * <p>Guards the two computed things the quick-look tab adds: the three-month renewal watch and the
 * top revenue-bearing opportunities. The renewal window is defined in calendar months, so a span of
 * up to ~92 days is legitimate; anything past that means the window query and the fractional
 * month-count have drifted apart.
 */
@SpringBootTest
class ManagerOverviewTest {

    /** 3 calendar months is at most ~92 days; 92 / 30.44 = 3.02, with a hair of rounding headroom. */
    private static final double MAX_MONTHS_TO_RENEWAL = 3.05;

    @Autowired
    private RevenueIntelligenceOrchestrator orchestrator;

    @Test
    void renewalWindowHoldsOnlyContractsEndingWithinThreeMonths() {
        ManagerOverviewPayload payload = orchestrator.managerOverview();
        List<ManagerOverviewPayload.RenewalRow> window = payload.renewalWindow();

        assertThat(window).as("the default dataset has contracts ending within three months").isNotEmpty();

        for (ManagerOverviewPayload.RenewalRow row : window) {
            assertThat(row.endDate()).as("renewal row %s end date", row.contractId()).isNotBlank();
            assertThat(row.arrUsd()).as("renewal row %s ARR", row.contractId()).isNotNull();
            assertThat(row.arrUsd().doubleValue())
                    .as("renewal row %s ARR is non-negative", row.contractId())
                    .isGreaterThanOrEqualTo(0.0);
            assertThat(row.monthsToRenewal()).as("renewal row %s months", row.contractId()).isNotNull();
            assertThat(row.monthsToRenewal().doubleValue())
                    .as("renewal row %s: %s ends in %s months -- the window query and the month "
                                    + "count have drifted apart",
                            row.contractId(), row.endDate(), row.monthsToRenewal())
                    .isBetween(0.0, MAX_MONTHS_TO_RENEWAL);
            assertThat(row.withinThreeMonths())
                    .as("renewal row %s must be flagged inside the window", row.contractId())
                    .isTrue();
        }
    }

    @Test
    void headlineKpisReconcileWithTheRowsBehindThem() {
        ManagerOverviewPayload payload = orchestrator.managerOverview();

        // "Contracts ending <= 3 mo" must equal the number of rows in the window.
        assertThat(kpi(payload.kpis(), "renewalsDueSoon").intValue())
                .as("renewal count KPI against the window size")
                .isEqualTo(payload.renewalWindow().size());

        // "ARR up for renewal" must equal the summed ARR of those same rows, to the cent.
        BigDecimal summed = payload.renewalWindow().stream()
                .map(ManagerOverviewPayload.RenewalRow::arrUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(kpi(payload.kpis(), "arrUpForRenewal"))
                .as("ARR-up-for-renewal KPI against the summed window ARR")
                .isEqualByComparingTo(summed);
    }

    @Test
    void topOpportunitiesAllCarryRevenueAndAreRankedByValue() {
        ManagerOverviewPayload payload = orchestrator.managerOverview();
        List<Signal> opportunities = payload.topOpportunities();

        BigDecimal previous = null;
        for (Signal signal : opportunities) {
            assertThat(signal.carriesRevenue())
                    .as("opportunity %s must be a revenue-bearing signal", signal.id())
                    .isTrue();
            assertThat(signal.estimatedValueUsd().doubleValue())
                    .as("opportunity %s value", signal.id())
                    .isGreaterThan(0.0);
            if (previous != null) {
                assertThat(signal.estimatedValueUsd())
                        .as("opportunities must be ranked by value, largest first")
                        .isLessThanOrEqualTo(previous);
            }
            previous = signal.estimatedValueUsd();
        }
    }

    private static BigDecimal kpi(List<ExecutiveDto.Kpi> kpis, String key) {
        return kpis.stream()
                .filter(k -> key.equals(k.key()))
                .map(ExecutiveDto.Kpi::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KPI with key " + key));
    }
}
