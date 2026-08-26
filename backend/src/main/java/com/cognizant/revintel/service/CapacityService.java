package com.cognizant.revintel.service;

import com.cognizant.revintel.dto.CapacityDto;
import com.cognizant.revintel.entity.Workforce;
import com.cognizant.revintel.model.ForecastRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns forecast dollars into delivery hours, headcount and hiring advice.
 *
 * <p><b>Not a Spring bean, and deliberately so.</b> It takes a plain {@code List<ForecastRow>} in
 * its constructor rather than injecting {@code ForecastingService} (spec 4.1). That buys three
 * things:
 *
 * <ul>
 *   <li><b>No duplicate computation.</b> A request needing both revenue and capacity numbers
 *       computes the forecast once and hands the same rows to both.</li>
 *   <li><b>Independent testability.</b> A unit test constructs it from a fixture list -- no
 *       database, no {@code ForecastingService}, no Spring context.</li>
 *   <li><b>Guaranteed consistency.</b> Capacity demand is a deterministic hours-per-dollar
 *       transform of <em>the exact same</em> forecast the revenue numbers came from, so a what-if
 *       override physically cannot reach one and miss the other.</li>
 * </ul>
 *
 * <p>Workforce rows are passed in for the same reason. The spec's constructor signature is about
 * not depending on {@code ForecastingService}; taking the workforce list as data rather than
 * reaching for a repository keeps the class equally free of Spring and equally easy to fixture.
 */
public class CapacityService {

    private final List<ForecastRow> forecastRows;
    private final List<Workforce> workforce;

    public CapacityService(List<ForecastRow> forecastRows, List<Workforce> workforce) {
        this.forecastRows = forecastRows == null ? List.of() : List.copyOf(forecastRows);
        this.workforce = workforce == null ? List.of() : List.copyOf(workforce);
    }

    // ==================================================================================
    // Demand
    // ==================================================================================

    /**
     * Forecast-implied delivery hours per resource group per quarter.
     *
     * <p>Each forecast row's weighted amount is converted with that opportunity type's labour
     * content and delivery rate, then split across resource groups by the standard mix. Weighted,
     * not gross: you staff for the revenue you expect to win, not the revenue you bid.
     */
    public List<CapacityDto.SkillDemandRow> skillDemandForecast() {
        Map<GroupQuarter, double[]> demand = new LinkedHashMap<>();

        for (ForecastRow row : forecastRows) {
            Map<String, Double> byGroup =
                    DeliveryEconomics.demandHoursByGroup(row.opportunityType(), row.weightedAmountUsd());
            for (Map.Entry<String, Double> entry : byGroup.entrySet()) {
                double[] cell = demand.computeIfAbsent(
                        new GroupQuarter(entry.getKey(), row.quarter()), k -> new double[3]);
                cell[0] += entry.getValue();
                cell[row.isWhitespace() ? 2 : 1] += entry.getValue();
            }
        }

        List<CapacityDto.SkillDemandRow> rows = new ArrayList<>();
        demand.forEach((key, hours) -> rows.add(new CapacityDto.SkillDemandRow(
                key.resourceGroup(),
                practiceOf(key.resourceGroup()),
                key.quarter(),
                DeliveryEconomics.money(hours[0]),
                fte(hours[0], key.resourceGroup()),
                DeliveryEconomics.money(hours[1]),
                DeliveryEconomics.money(hours[2]))));

        rows.sort(Comparator.comparing(CapacityDto.SkillDemandRow::resourceGroup)
                .thenComparing(CapacityDto.SkillDemandRow::quarter, Quarters.ORDER));
        return rows;
    }

    /** Total forecast demand hours per resource group, summed across the forecast horizon. */
    public Map<String, Double> totalDemandHoursByGroup() {
        Map<String, Double> totals = new TreeMap<>();
        for (ForecastRow row : forecastRows) {
            DeliveryEconomics.demandHoursByGroup(row.opportunityType(), row.weightedAmountUsd())
                    .forEach((group, hours) -> totals.merge(group, hours, Double::sum));
        }
        return totals;
    }

    // ==================================================================================
    // Capacity
    // ==================================================================================

    /** What each resource group can sustainably deliver in a quarter. */
    public List<CapacityDto.CapacityRow> sustainableCapacity() {
        List<CapacityDto.CapacityRow> rows = new ArrayList<>();
        for (Workforce group : workforce) {
            double quarterlyCapacity = group.getHeadcount()
                    * group.getAnnualCapacityHoursPerFte().doubleValue() / 4.0;
            double sustainable = quarterlyCapacity * group.getTargetUtilization().doubleValue();

            rows.add(new CapacityDto.CapacityRow(
                    group.getResourceGroup(),
                    group.getPractice(),
                    group.getHeadcount(),
                    group.getContractorHeadcount(),
                    DeliveryEconomics.money(quarterlyCapacity),
                    DeliveryEconomics.money(sustainable),
                    DeliveryEconomics.money(sustainable * group.getAvgBillRateUsd().doubleValue()),
                    group.getCurrentUtilization(),
                    group.getTargetUtilization(),
                    group.getAvgBillRateUsd()));
        }
        rows.sort(Comparator.comparing(CapacityDto.CapacityRow::resourceGroup));
        return rows;
    }

    /** Projected utilisation per resource group per quarter, with a plain-language status. */
    public List<CapacityDto.UtilizationProjectionRow> utilizationProjection() {
        Map<String, Workforce> byGroup = workforceByGroup();
        List<CapacityDto.UtilizationProjectionRow> rows = new ArrayList<>();

        for (CapacityDto.SkillDemandRow demand : skillDemandForecast()) {
            Workforce group = byGroup.get(demand.resourceGroup());
            if (group == null) {
                continue;
            }
            double capacity = group.getHeadcount()
                    * group.getAnnualCapacityHoursPerFte().doubleValue() / 4.0;
            double utilization = capacity > 0 ? demand.demandHours().doubleValue() / capacity : 0.0;

            rows.add(new CapacityDto.UtilizationProjectionRow(
                    demand.resourceGroup(),
                    demand.practice(),
                    demand.quarter(),
                    demand.demandHours(),
                    DeliveryEconomics.money(capacity),
                    DeliveryEconomics.rate(utilization),
                    statusFor(utilization, group.getTargetUtilization().doubleValue())));
        }
        return rows;
    }

    private static String statusFor(double utilization, double target) {
        if (utilization < target - 0.15) {
            return "Under-utilised";
        }
        if (utilization <= target + 0.05) {
            return "Healthy";
        }
        if (utilization <= 1.0) {
            return "Stretched";
        }
        return "Over capacity";
    }

    // ==================================================================================
    // Hiring
    // ==================================================================================

    /**
     * Hiring and contractor advice per resource group.
     *
     * <p>Permanent hires close the gap against <em>average</em> demand across the forecast
     * quarters; contractors absorb the peak above that. Sizing FTE against the peak quarter is the
     * mistake that produced 5-6x headcount recommendations in the reference build.
     */
    public List<CapacityDto.HiringRecommendation> hiringRecommendations() {
        Map<String, List<Double>> demandByGroup = new TreeMap<>();
        for (CapacityDto.SkillDemandRow row : skillDemandForecast()) {
            demandByGroup.computeIfAbsent(row.resourceGroup(), k -> new ArrayList<>())
                    .add(row.demandHours().doubleValue());
        }

        List<CapacityDto.HiringRecommendation> out = new ArrayList<>();
        for (Workforce group : workforce) {
            List<Double> quarters = demandByGroup.getOrDefault(group.getResourceGroup(), List.of());
            double average = quarters.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double peak = quarters.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

            double perFte = group.getAnnualCapacityHoursPerFte().doubleValue() / 4.0
                    * group.getTargetUtilization().doubleValue();
            double sustainable = group.getHeadcount() * perFte;

            double averageGap = Math.max(0.0, average - sustainable);
            int fteHires = perFte > 0 ? (int) Math.ceil(averageGap / perFte) : 0;
            // Contractors cover only the spike above the level permanent hires now handle.
            double peakGap = Math.max(0.0, peak - sustainable - fteHires * perFte);
            int contractors = perFte > 0 ? (int) Math.ceil(peakGap / perFte) : 0;

            double share = group.getHeadcount() > 0
                    ? (double) fteHires / group.getHeadcount() : 0.0;
            String urgency = fteHires == 0 ? "None" : share >= 0.25 ? "High" : share >= 0.10 ? "Medium" : "Low";

            String rationale = fteHires == 0 && contractors == 0
                    ? String.format("%d FTE cover average forecast demand of %.0f hours per quarter "
                                    + "against %.0f sustainable hours. No hiring indicated.",
                            group.getHeadcount(), average, sustainable)
                    : String.format("Average forecast demand of %.0f hours per quarter exceeds %.0f "
                                    + "sustainable hours from %d FTE. %d hire(s) close the average gap; "
                                    + "%d contractor(s) cover the %.0f-hour peak quarter.",
                            average, sustainable, group.getHeadcount(), fteHires, contractors, peak);

            out.add(new CapacityDto.HiringRecommendation(
                    group.getResourceGroup(),
                    group.getPractice(),
                    group.getHeadcount(),
                    group.getContractorHeadcount(),
                    DeliveryEconomics.money(average),
                    DeliveryEconomics.money(peak),
                    DeliveryEconomics.money(sustainable),
                    DeliveryEconomics.money(averageGap),
                    DeliveryEconomics.money(peakGap),
                    fteHires,
                    contractors,
                    DeliveryEconomics.rate(share),
                    urgency,
                    rationale));
        }
        out.sort(Comparator.comparing(CapacityDto.HiringRecommendation::recommendedFteHires).reversed()
                .thenComparing(CapacityDto.HiringRecommendation::resourceGroup));
        return out;
    }

    // ==================================================================================
    // Summary
    // ==================================================================================

    public CapacityDto.CapacitySummary summary() {
        List<CapacityDto.HiringRecommendation> hiring = hiringRecommendations();
        List<CapacityDto.SkillDemandRow> demand = skillDemandForecast();

        int headcount = workforce.stream().mapToInt(Workforce::getHeadcount).sum();
        int contractors = workforce.stream().mapToInt(Workforce::getContractorHeadcount).sum();
        double totalDemand = demand.stream().mapToDouble(r -> r.demandHours().doubleValue()).sum();

        // Sustainable hours across the same number of quarters the demand spans, so the fleet
        // utilisation figure compares like with like.
        long quarterCount = demand.stream().map(CapacityDto.SkillDemandRow::quarter).distinct().count();
        double sustainablePerQuarter = workforce.stream()
                .mapToDouble(w -> w.getHeadcount() * w.getAnnualCapacityHoursPerFte().doubleValue() / 4.0
                        * w.getTargetUtilization().doubleValue())
                .sum();
        double totalSustainable = sustainablePerQuarter * Math.max(1, quarterCount);

        int fteHires = hiring.stream().mapToInt(CapacityDto.HiringRecommendation::recommendedFteHires).sum();
        int contractorHires = hiring.stream()
                .mapToInt(CapacityDto.HiringRecommendation::recommendedContractors).sum();
        long overCapacity = utilizationProjection().stream()
                .filter(r -> "Over capacity".equals(r.status())).count();

        return new CapacityDto.CapacitySummary(
                headcount,
                contractors,
                DeliveryEconomics.money(totalDemand),
                DeliveryEconomics.money(totalSustainable),
                totalSustainable > 0
                        ? DeliveryEconomics.rate(totalDemand / totalSustainable * DeliveryEconomics.TARGET_UTILIZATION)
                        : null,
                fteHires,
                contractorHires,
                headcount > 0 ? DeliveryEconomics.rate((double) fteHires / headcount) : null,
                (int) overCapacity,
                demand.stream().map(CapacityDto.SkillDemandRow::quarter).distinct()
                        .sorted(Quarters.ORDER).toList());
    }

    // ==================================================================================
    // Plumbing
    // ==================================================================================

    private Map<String, Workforce> workforceByGroup() {
        Map<String, Workforce> map = new LinkedHashMap<>();
        for (Workforce group : workforce) {
            map.put(group.getResourceGroup(), group);
        }
        return map;
    }

    private String practiceOf(String resourceGroup) {
        return workforce.stream()
                .filter(w -> resourceGroup.equals(w.getResourceGroup()))
                .map(Workforce::getPractice)
                .findFirst()
                .orElse("Unassigned");
    }

    private BigDecimal fte(double hours, String resourceGroup) {
        double perFte = DeliveryEconomics.quarterlyCapacityHours(resourceGroup)
                * DeliveryEconomics.TARGET_UTILIZATION;
        return perFte > 0
                ? BigDecimal.valueOf(hours / perFte).setScale(2, RoundingMode.HALF_UP)
                : null;
    }

    private record GroupQuarter(String resourceGroup, String quarter) {
    }
}
