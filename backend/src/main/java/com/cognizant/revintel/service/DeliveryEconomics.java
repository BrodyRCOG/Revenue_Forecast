package com.cognizant.revintel.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dollars-to-hours model, and the constants every deterministic calculation shares.
 *
 * <p><b>MIRROR OF {@code data-tools/reference_data.py}.</b> The Python generator sizes workforce
 * headcount off the demand these numbers imply; this class converts forecast dollars into demand
 * hours the same way. When the two drift apart, capacity demand stops matching the workforce that
 * was generated for it and hiring recommendations go absurd -- that is exactly the "hire 5-6x your
 * bench" bug from the reference build.
 *
 * <p>Two guards keep them together: {@code data_evals.capacity_vs_historical_demand_plausibility}
 * on the Python side, and {@code DeliveryEconomicsParityTest} on this side, which reads
 * {@code reference_data.py} and asserts the values still match.
 */
public final class DeliveryEconomics {

    // -- opportunity taxonomy ----------------------------------------------------------

    public static final List<String> OPPORTUNITY_TYPES = List.of(
            "Infrastructure Refresh", "Modernization", "Managed Services", "Security", "Cloud Migration");

    public static final List<String> PRACTICES = List.of(
            "Infrastructure", "Applications", "Managed Services", "Security", "Cloud");

    public static final Map<String, String> PRACTICE_BY_OPPORTUNITY_TYPE = Map.of(
            "Infrastructure Refresh", "Infrastructure",
            "Modernization", "Applications",
            "Managed Services", "Managed Services",
            "Security", "Security",
            "Cloud Migration", "Cloud");

    public static final List<String> RESOURCE_GROUPS = List.of(
            "Network Engineering",
            "Data Center & Compute",
            "Cloud & Platform Engineering",
            "Application Modernization",
            "Security Engineering",
            "Service Desk & Operations",
            "Project & Program Management");

    // -- dollars to hours --------------------------------------------------------------

    /**
     * Fraction of deal value that is delivery labour. The rest is hardware / licence
     * pass-through and consumes no delivery hours -- which is why a hardware-heavy
     * Infrastructure Refresh generates far fewer hours per dollar than a Modernization deal.
     */
    public static final Map<String, Double> LABOR_CONTENT = Map.of(
            "Infrastructure Refresh", 0.35,
            "Modernization", 0.80,
            "Managed Services", 0.60,
            "Security", 0.55,
            "Cloud Migration", 0.70);

    /** Realised revenue per delivery hour, by opportunity type. */
    public static final Map<String, Double> DELIVERY_RATE_USD_PER_HOUR = Map.of(
            "Infrastructure Refresh", 175.0,
            "Modernization", 195.0,
            "Managed Services", 135.0,
            "Security", 205.0,
            "Cloud Migration", 200.0);

    /** How each opportunity type's hours split across resource groups. Each row sums to 1.0. */
    public static final Map<String, Map<String, Double>> RESOURCE_MIX = Map.of(
            "Infrastructure Refresh", Map.of(
                    "Network Engineering", 0.45,
                    "Data Center & Compute", 0.35,
                    "Project & Program Management", 0.12,
                    "Service Desk & Operations", 0.08),
            "Modernization", Map.of(
                    "Application Modernization", 0.55,
                    "Cloud & Platform Engineering", 0.25,
                    "Project & Program Management", 0.12,
                    "Data Center & Compute", 0.08),
            "Managed Services", Map.of(
                    "Service Desk & Operations", 0.55,
                    "Network Engineering", 0.15,
                    "Cloud & Platform Engineering", 0.15,
                    "Security Engineering", 0.08,
                    "Project & Program Management", 0.07),
            "Security", Map.of(
                    "Security Engineering", 0.65,
                    "Network Engineering", 0.15,
                    "Cloud & Platform Engineering", 0.10,
                    "Project & Program Management", 0.10),
            "Cloud Migration", Map.of(
                    "Cloud & Platform Engineering", 0.55,
                    "Application Modernization", 0.20,
                    "Data Center & Compute", 0.13,
                    "Project & Program Management", 0.12));

    // -- capacity ----------------------------------------------------------------------

    /** Billable-capable hours per FTE per year, before utilisation. */
    public static final Map<String, Double> ANNUAL_CAPACITY_HOURS = Map.of(
            "Network Engineering", 1720.0,
            "Data Center & Compute", 1720.0,
            "Cloud & Platform Engineering", 1720.0,
            "Application Modernization", 1720.0,
            "Security Engineering", 1720.0,
            "Service Desk & Operations", 1760.0,
            "Project & Program Management", 1680.0);

    public static final double TARGET_UTILIZATION = 0.85;

    /** Above this, a resource group raises an over-utilisation signal. */
    public static final double OVER_UTILIZATION_THRESHOLD = 0.92;

    // -- signal / forecast tuning ------------------------------------------------------

    /**
     * Fraction of a whitespace signal's estimated value assumed to convert into real pipeline,
     * applied before the historical win rate. Whitespace is not pipeline; discounting it twice
     * (conversion, then win rate) is what keeps it a modest addition rather than a fantasy.
     */
    public static final double WHITESPACE_CONVERSION = 0.25;

    /**
     * Share of an at-risk category estate a single refresh engagement would realistically
     * address. A client with 400 end-of-support switches does not replace all 400 in one deal.
     */
    public static final double REFRESH_ADDRESSABLE_SHARE = 0.30;

    /** Annual support spend as a fraction of hardware replacement value. Mirrors the generator. */
    public static final double SUPPORT_COST_RATIO = 0.18;

    /** A renew-and-expand play on an at-risk contract is worth this multiple of current ARR. */
    public static final double CONTRACT_EXPANSION_MULTIPLE = 1.15;

    /** Pseudo-observations pulling a group's win rate towards its parent rate. */
    public static final double WIN_RATE_PRIOR_STRENGTH = 12.0;

    /**
     * A remaining target at or below this counts as already met: the coverage ratio returns null
     * instead of dividing by near-zero. This is the fix for the 82,427x coverage ratio.
     */
    public static final BigDecimal COVERAGE_TARGET_MET_EPSILON_USD = BigDecimal.valueOf(25_000);

    public static final int MONEY_SCALE = 2;
    public static final int RATE_SCALE = 4;

    private DeliveryEconomics() {
    }

    // -- derivations -------------------------------------------------------------------

    public static String practiceFor(String opportunityType) {
        return PRACTICE_BY_OPPORTUNITY_TYPE.getOrDefault(opportunityType, "Infrastructure");
    }

    public static double laborContent(String opportunityType) {
        return LABOR_CONTENT.getOrDefault(opportunityType, 0.55);
    }

    public static double deliveryRate(String opportunityType) {
        return DELIVERY_RATE_USD_PER_HOUR.getOrDefault(opportunityType, 180.0);
    }

    /** Hours one FTE in this group can offer in a quarter, before utilisation. */
    public static double quarterlyCapacityHours(String resourceGroup) {
        return ANNUAL_CAPACITY_HOURS.getOrDefault(resourceGroup, 1720.0) / 4.0;
    }

    /** Total delivery hours a deal of this type and size implies. */
    public static double demandHours(String opportunityType, BigDecimal amountUsd) {
        if (amountUsd == null) {
            return 0.0;
        }
        return amountUsd.doubleValue() * laborContent(opportunityType) / deliveryRate(opportunityType);
    }

    /** {@link #demandHours} split across resource groups, insertion-ordered by share descending. */
    public static Map<String, Double> demandHoursByGroup(String opportunityType, BigDecimal amountUsd) {
        double total = demandHours(opportunityType, amountUsd);
        Map<String, Double> mix = RESOURCE_MIX.get(opportunityType);
        Map<String, Double> out = new LinkedHashMap<>();
        if (mix == null) {
            return out;
        }
        mix.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> out.put(e.getKey(), total * e.getValue()));
        return out;
    }

    /** The resource group carrying the largest share of this opportunity type's hours. */
    public static String primaryResourceGroup(String opportunityType) {
        Map<String, Double> mix = RESOURCE_MIX.get(opportunityType);
        if (mix == null) {
            return RESOURCE_GROUPS.get(0);
        }
        return mix.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(RESOURCE_GROUPS.get(0));
    }

    /**
     * Lifecycle severity band from months remaining until end-of-support. Mirrors
     * {@code reference_data.eol_severity}.
     */
    public static String eolSeverity(double monthsToEndOfSupport) {
        if (Double.isNaN(monthsToEndOfSupport)) {
            return "Low";
        }
        if (monthsToEndOfSupport < 0) {
            return "Critical";
        }
        if (monthsToEndOfSupport <= 12) {
            return "High";
        }
        if (monthsToEndOfSupport <= 24) {
            return "Medium";
        }
        return "Low";
    }

    public static BigDecimal money(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal rate(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(value).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
}
