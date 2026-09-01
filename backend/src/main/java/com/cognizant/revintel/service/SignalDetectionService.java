package com.cognizant.revintel.service;

import com.cognizant.revintel.dto.ManagerOverviewPayload;
import com.cognizant.revintel.entity.Client;
import com.cognizant.revintel.entity.Contract;
import com.cognizant.revintel.entity.Opportunity;
import com.cognizant.revintel.entity.Workforce;
import com.cognizant.revintel.model.Signal;
import com.cognizant.revintel.repository.ClientRepository;
import com.cognizant.revintel.repository.ContractRepository;
import com.cognizant.revintel.repository.InstalledBaseRepository;
import com.cognizant.revintel.repository.OpportunityRepository;
import com.cognizant.revintel.repository.WorkforceRepository;
import com.cognizant.revintel.repository.projection.AssetLifecycleRow;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Notices things <em>before</em> they become pipeline.
 *
 * <p>Three rules, all pure joins and comparisons over repository data:
 *
 * <ol>
 *   <li>hardware at critical or high end-of-support risk where the client has no open refresh or
 *       modernization opportunity;</li>
 *   <li>a contract graded High or Critical renewal risk where the client has no open
 *       managed-services opportunity;</li>
 *   <li>a resource group already above the over-utilisation threshold.</li>
 * </ol>
 *
 * <p>No LLM, no model, no probability draw -- every field on every signal traces back to a stored
 * row. That is why the narration layer can safely be asked to describe these and nothing else.
 */
@Service
public class SignalDetectionService {

    /** Opportunity types that count as "somebody is already selling a refresh here". */
    private static final Set<String> REFRESH_COVERING_TYPES =
            Set.of("Infrastructure Refresh", "Modernization");

    private static final Set<String> URGENT_SEVERITIES = Set.of("Critical", "High");

    private final InstalledBaseRepository installedBase;
    private final ContractRepository contracts;
    private final OpportunityRepository opportunities;
    private final WorkforceRepository workforce;
    private final ClientRepository clients;
    private final AsOfProvider asOfProvider;

    public SignalDetectionService(InstalledBaseRepository installedBase,
                                  ContractRepository contracts,
                                  OpportunityRepository opportunities,
                                  WorkforceRepository workforce,
                                  ClientRepository clients,
                                  AsOfProvider asOfProvider) {
        this.installedBase = installedBase;
        this.contracts = contracts;
        this.opportunities = opportunities;
        this.workforce = workforce;
        this.clients = clients;
        this.asOfProvider = asOfProvider;
    }

    /** All three rules, most valuable first (capacity signals, which carry no revenue, last). */
    public List<Signal> allSignals() {
        List<Opportunity> openPipeline = opportunities.findOpenPipeline();
        List<Signal> signals = new ArrayList<>();
        signals.addAll(lifecycleSignals(openPipeline));
        signals.addAll(contractRenewalSignals(openPipeline));
        signals.addAll(capacityPressureSignals());
        signals.sort(Comparator
                .comparing((Signal s) -> severityRank(s.severity()))
                .thenComparing(s -> s.estimatedValueUsd() == null ? BigDecimal.ZERO : s.estimatedValueUsd(),
                        Comparator.reverseOrder())
                .thenComparing(Signal::id));
        return signals;
    }

    public List<Signal> lifecycleSignals() {
        return lifecycleSignals(opportunities.findOpenPipeline());
    }

    // -- rule 1: end-of-support estate with nobody selling a refresh -------------------

    private List<Signal> lifecycleSignals(List<Opportunity> openPipeline) {
        Set<String> alreadyCovered = clientsWithOpenOpportunityOfType(openPipeline, REFRESH_COVERING_TYPES);
        List<String> forecastQuarters = asOfProvider.forecastQuarters();

        // Group urgent assets by (client, category) -- one signal per estate, not per asset.
        Map<String, Estate> estates = new LinkedHashMap<>();
        for (AssetLifecycleRow row : installedBase.withLifecycle()) {
            if (alreadyCovered.contains(row.clientId()) || row.endOfSupport() == null) {
                continue;
            }
            String severity = DeliveryEconomics.eolSeverity(asOfProvider.monthsUntil(row.endOfSupport()));
            if (!URGENT_SEVERITIES.contains(severity)) {
                continue;
            }
            estates.computeIfAbsent(row.clientId() + "|" + row.category(), k -> new Estate(row))
                    .add(row, severity);
        }

        List<Signal> signals = new ArrayList<>();
        for (Estate estate : estates.values()) {
            // Replacement value is derived from the annual support spend already on the asset
            // record, so there is no second cost table to drift out of sync with the generator.
            double replacementValue = estate.annualSupportCost / DeliveryEconomics.SUPPORT_COST_RATIO;
            double addressable = replacementValue * DeliveryEconomics.REFRESH_ADDRESSABLE_SHARE;
            // Hardware is (1 - labour content) of a refresh deal; gross the addressable hardware
            // up to a whole deal so the services element is included.
            double dealValue = addressable / (1.0 - DeliveryEconomics.laborContent("Infrastructure Refresh"));

            // Critical estates are already out of support: sell now. High-risk ones have a
            // quarter or two of runway.
            String quarter = forecastQuarters.get("Critical".equals(estate.severity) ? 0
                    : Math.min(1, forecastQuarters.size() - 1));

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("assetRecords", estate.assetCount);
            evidence.put("units", estate.units);
            evidence.put("annualSupportCostUsd", DeliveryEconomics.money(estate.annualSupportCost));
            evidence.put("estimatedRefreshValueUsd", DeliveryEconomics.money(dealValue));
            evidence.put("monthsPastEndOfSupport",
                    DeliveryEconomics.rate(Math.max(0, -estate.earliestMonthsRemaining)));
            evidence.put("monthsToEndOfSupport", DeliveryEconomics.rate(estate.earliestMonthsRemaining));
            evidence.put("missionCriticalUnits", estate.missionCriticalUnits);
            evidence.put("realAnchorModels", estate.realAnchorModels);

            String window = estate.earliestMonthsRemaining < 0
                    ? String.format("already %.0f months past end of support", -estate.earliestMonthsRemaining)
                    : String.format("reaches end of support in %.0f months", estate.earliestMonthsRemaining);

            signals.add(new Signal(
                    "SIG-EOL-" + estate.clientId + "-" + slug(estate.category),
                    Signal.TYPE_LIFECYCLE,
                    estate.severity,
                    estate.clientId,
                    estate.clientName,
                    estate.segment,
                    String.format("%s %s estate %s", estate.clientName, estate.category.toLowerCase(), window),
                    String.format("%d %s asset record(s) covering %d unit(s) from %s, %s. "
                                    + "No open refresh or modernization opportunity exists for this client.",
                            estate.assetCount, estate.category.toLowerCase(), estate.units,
                            String.join(", ", estate.oems), window),
                    "Open a refresh conversation before the support lapse forces an emergency buy.",
                    "Infrastructure Refresh",
                    DeliveryEconomics.practiceFor("Infrastructure Refresh"),
                    quarter,
                    DeliveryEconomics.money(dealValue),
                    evidence));
        }
        return signals;
    }

    // -- rule 2: at-risk contract with no expansion play ------------------------------

    public List<Signal> contractRenewalSignals() {
        return contractRenewalSignals(opportunities.findOpenPipeline());
    }

    private List<Signal> contractRenewalSignals(List<Opportunity> openPipeline) {
        Set<String> alreadyCovered = clientsWithOpenOpportunityOfType(openPipeline, Set.of("Managed Services"));
        Map<String, Client> clientsById = clientsById();
        List<String> forecastQuarters = asOfProvider.forecastQuarters();
        String firstQuarter = forecastQuarters.get(0);
        String lastQuarter = forecastQuarters.get(forecastQuarters.size() - 1);

        List<Signal> signals = new ArrayList<>();
        for (Contract contract : contracts.findAtRisk()) {
            if (alreadyCovered.contains(contract.getClientId())) {
                continue;
            }
            Client client = clientsById.get(contract.getClientId());
            if (client == null) {
                continue;
            }
            double arr = contract.getArrUsd() == null ? 0.0 : contract.getArrUsd().doubleValue();
            double dealValue = arr * DeliveryEconomics.CONTRACT_EXPANSION_MULTIPLE;
            double monthsToRenewal = asOfProvider.monthsUntil(contract.getEndDate());

            // Act in the quarter the renewal lands in, clamped into the forecast horizon.
            String quarter = contract.getEndDate() == null ? firstQuarter
                    : clampToHorizon(Quarters.labelOf(contract.getEndDate()), firstQuarter, lastQuarter);

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("currentArrUsd", DeliveryEconomics.money(arr));
            evidence.put("expansionValueUsd", DeliveryEconomics.money(dealValue));
            evidence.put("renewalProbability", contract.getRenewalProbability());
            evidence.put("monthsToRenewal", DeliveryEconomics.rate(monthsToRenewal));
            evidence.put("npsScore", contract.getNpsScore());
            evidence.put("autoRenew", contract.isAutoRenew());

            signals.add(new Signal(
                    "SIG-CTR-" + contract.getId(),
                    Signal.TYPE_CONTRACT,
                    "Critical".equals(contract.getRenewalRisk()) ? "Critical" : "High",
                    client.getId(),
                    client.getName(),
                    client.getSegment(),
                    String.format("%s %s contract is %s renewal risk",
                            client.getName(), contract.getServiceType().toLowerCase(),
                            contract.getRenewalRisk().toLowerCase()),
                    String.format("%s of %s ARR renews in %.0f months at %s risk"
                                    + "%s. No open managed-services opportunity exists for this client.",
                            contract.getServiceType(), formatUsd(arr), monthsToRenewal,
                            contract.getRenewalRisk().toLowerCase(),
                            contract.isAutoRenew() ? " (auto-renew)" : " with no auto-renew"),
                    "Lead with a renew-and-expand proposal rather than defending the existing scope.",
                    "Managed Services",
                    DeliveryEconomics.practiceFor("Managed Services"),
                    quarter,
                    DeliveryEconomics.money(dealValue),
                    evidence));
        }
        return signals;
    }

    // -- renewal watch: every contract ending inside the three-month window -----------

    /** Months of forward horizon the Manager Overview renewal watch looks across. */
    public static final int RENEWAL_WINDOW_MONTHS = 3;

    /**
     * Contracts whose term ends within {@link #RENEWAL_WINDOW_MONTHS} months of the as-of date, most
     * imminent first -- the standing renewal rule ("any contract within 3 months of its end date
     * must be flagged") applied mechanically rather than eyeballed.
     *
     * <p>Unlike {@link #contractRenewalSignals()}, this is not filtered by renewal risk or by
     * whether an expansion play already exists: a business manager wants to see <em>every</em>
     * contract coming up for renewal, regardless of grade. Pure join logic over the contract and
     * client repositories, with {@code monthsToRenewal} routed through {@link DeliveryEconomics#rate}
     * so no non-finite value can reach the JSON boundary.
     */
    public List<ManagerOverviewPayload.RenewalRow> renewalWindow() {
        java.time.LocalDate asOf = asOfProvider.asOf();
        java.time.LocalDate cutoff = asOf.plusMonths(RENEWAL_WINDOW_MONTHS);
        Map<String, Client> clientsById = clientsById();

        List<ManagerOverviewPayload.RenewalRow> rows = new ArrayList<>();
        for (Contract contract : contracts.findEndingBetween(asOf, cutoff)) {
            Client client = clientsById.get(contract.getClientId());
            double arr = contract.getArrUsd() == null ? 0.0 : contract.getArrUsd().doubleValue();
            double monthsToRenewal = asOfProvider.monthsUntil(contract.getEndDate());

            rows.add(new ManagerOverviewPayload.RenewalRow(
                    contract.getId(),
                    contract.getClientId(),
                    client == null ? contract.getClientId() : client.getName(),
                    client == null ? null : client.getSegment(),
                    contract.getServiceType(),
                    DeliveryEconomics.money(arr),
                    contract.getEndDate().toString(),
                    DeliveryEconomics.rate(monthsToRenewal),
                    contract.getRenewalRisk(),
                    contract.isAutoRenew(),
                    contract.getNpsScore(),
                    !contract.getEndDate().isAfter(cutoff)));
        }
        return rows;
    }

    // -- rule 3: delivery group already over its ceiling ------------------------------

    public List<Signal> capacityPressureSignals() {
        List<Signal> signals = new ArrayList<>();
        List<Workforce> hot = workforce.findByCurrentUtilizationGreaterThan(
                BigDecimal.valueOf(DeliveryEconomics.OVER_UTILIZATION_THRESHOLD));

        for (Workforce group : hot) {
            double utilization = group.getCurrentUtilization().doubleValue();
            double target = group.getTargetUtilization().doubleValue();

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("headcount", group.getHeadcount());
            evidence.put("contractorHeadcount", group.getContractorHeadcount());
            evidence.put("currentUtilization", DeliveryEconomics.rate(utilization));
            evidence.put("targetUtilization", DeliveryEconomics.rate(target));
            evidence.put("utilizationOverTarget", DeliveryEconomics.rate(utilization - target));

            signals.add(new Signal(
                    "SIG-CAP-" + group.getId(),
                    Signal.TYPE_CAPACITY,
                    utilization >= 1.0 ? "Critical" : "High",
                    null, null, null,
                    String.format("%s is running at %.0f%% utilisation",
                            group.getResourceGroup(), utilization * 100),
                    String.format("%d FTE plus %d contractor(s) at %.0f%% against a %.0f%% target. "
                                    + "New work in this group needs capacity before it needs a proposal.",
                            group.getHeadcount(), group.getContractorHeadcount(),
                            utilization * 100, target * 100),
                    "Add capacity or re-sequence delivery before committing new work to this group.",
                    // No revenue attached: this is an operational constraint, not an opportunity,
                    // so it never becomes a whitespace forecast row.
                    null,
                    group.getPractice(),
                    null,
                    null,
                    evidence));
        }
        return signals;
    }

    // -- helpers ----------------------------------------------------------------------

    private Set<String> clientsWithOpenOpportunityOfType(List<Opportunity> openPipeline, Set<String> types) {
        Set<String> covered = new HashSet<>();
        for (Opportunity opportunity : openPipeline) {
            if (types.contains(opportunity.getOpportunityType())) {
                covered.add(opportunity.getClientId());
            }
        }
        return covered;
    }

    private Map<String, Client> clientsById() {
        Map<String, Client> map = new HashMap<>();
        for (Client client : clients.findAll()) {
            map.put(client.getId(), client);
        }
        return map;
    }

    private static String clampToHorizon(String quarter, String first, String last) {
        if (Quarters.indexOf(quarter) < Quarters.indexOf(first)) {
            return first;
        }
        if (Quarters.indexOf(quarter) > Quarters.indexOf(last)) {
            return last;
        }
        return quarter;
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "Critical" -> 0;
            case "High" -> 1;
            case "Medium" -> 2;
            default -> 3;
        };
    }

    private static String slug(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9]+", "-").toUpperCase();
    }

    private static String formatUsd(double value) {
        if (value >= 1_000_000) {
            return String.format("$%.1fM", value / 1_000_000);
        }
        return String.format("$%.0fk", value / 1_000);
    }

    /** Accumulator for one client's at-risk assets in one category. */
    private final class Estate {
        private final String clientId;
        private final String clientName;
        private final String segment;
        private final String category;
        private final Set<String> oems = new java.util.LinkedHashSet<>();
        private final Map<String, Boolean> models = new TreeMap<>();
        private int assetCount;
        private int units;
        private int missionCriticalUnits;
        private int realAnchorModels;
        private double annualSupportCost;
        private double earliestMonthsRemaining = Double.MAX_VALUE;
        private String severity = "High";

        private Estate(AssetLifecycleRow seed) {
            this.clientId = seed.clientId();
            this.clientName = seed.clientName();
            this.segment = seed.segment();
            this.category = seed.category();
        }

        private void add(AssetLifecycleRow row, String rowSeverity) {
            assetCount++;
            int quantity = row.quantity() == null ? 0 : row.quantity();
            units += quantity;
            if ("Mission Critical".equals(row.criticality())) {
                missionCriticalUnits += quantity;
            }
            if (row.annualSupportCostUsd() != null) {
                annualSupportCost += row.annualSupportCostUsd().doubleValue();
            }
            oems.add(row.oem());
            if (models.putIfAbsent(row.oemModelId(), row.realAnchor()) == null && row.realAnchor()) {
                realAnchorModels++;
            }
            double months = asOfProvider.monthsUntil(row.endOfSupport());
            earliestMonthsRemaining = Math.min(earliestMonthsRemaining, months);
            if ("Critical".equals(rowSeverity)) {
                severity = "Critical";
            }
        }
    }
}
