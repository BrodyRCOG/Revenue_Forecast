package com.cognizant.revintel.service;

import com.cognizant.revintel.dto.ForecastDto;
import com.cognizant.revintel.entity.Client;
import com.cognizant.revintel.entity.Opportunity;
import com.cognizant.revintel.entity.Target;
import com.cognizant.revintel.model.Assumptions;
import com.cognizant.revintel.model.ForecastRow;
import com.cognizant.revintel.model.Signal;
import com.cognizant.revintel.model.WinRateModel;
import com.cognizant.revintel.repository.ClientRepository;
import com.cognizant.revintel.repository.OpportunityRepository;
import com.cognizant.revintel.repository.TargetRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every revenue number in the app. Plain Java, no model calls, no randomness -- run it twice on
 * the same dataset and you get the same answer, which is what makes the narration layer safe.
 *
 * <p>Forecast revenue has two sources:
 *
 * <ul>
 *   <li><b>pipeline</b> -- open CRM opportunities, weighted by a hierarchically smoothed
 *       historical win rate for their (segment, opportunity type);</li>
 *   <li><b>whitespace</b> -- revenue-bearing signals from {@link SignalDetectionService} that are
 *       not in the sales cycle at all, discounted twice: once by a conversion assumption, then by
 *       the same historical win rate.</li>
 * </ul>
 *
 * <p>All aggregations take a {@code List<ForecastRow>} rather than fetching their own data, so a
 * what-if scenario is just the same functions over a differently-built row list -- and capacity
 * gets handed the identical rows (spec 4.1).
 *
 * <p>Deliberately not built (spec section 8): there is no forecast snapshot table and no
 * calibration loop comparing past predictions to outcomes. Forecasts are recomputed live on every
 * request, which is why {@code actualVsProjected} reports {@code null} projections for quarters
 * that have already closed instead of inventing a retrospective one.
 */
@Service
public class ForecastingService {

    private final OpportunityRepository opportunities;
    private final ClientRepository clients;
    private final TargetRepository targets;
    private final SignalDetectionService signalDetection;
    private final AsOfProvider asOfProvider;

    public ForecastingService(OpportunityRepository opportunities,
                              ClientRepository clients,
                              TargetRepository targets,
                              SignalDetectionService signalDetection,
                              AsOfProvider asOfProvider) {
        this.opportunities = opportunities;
        this.clients = clients;
        this.targets = targets;
        this.signalDetection = signalDetection;
        this.asOfProvider = asOfProvider;
    }

    // ==================================================================================
    // Win rates
    // ==================================================================================

    /**
     * Hierarchically smoothed win rates fitted on closed history.
     *
     * <p>Shrinking each level towards its parent by {@code WIN_RATE_PRIOR_STRENGTH}
     * pseudo-observations is what stops a (segment, type) cell with three closed deals from
     * reporting a confident 67%.
     */
    public WinRateModel winRates() {
        List<Opportunity> closed = opportunities.findClosed();
        Map<String, Client> clientsById = clientsById();

        int closedCount = closed.size();
        int wonCount = (int) closed.stream().filter(o -> "Won".equals(o.getStatus())).count();
        double globalRate = closedCount == 0 ? 0.0 : (double) wonCount / closedCount;

        Map<String, int[]> byType = new TreeMap<>();
        Map<String, int[]> bySegmentType = new TreeMap<>();

        for (Opportunity opportunity : closed) {
            Client client = clientsById.get(opportunity.getClientId());
            String segment = client == null ? "Unknown" : client.getSegment();
            boolean won = "Won".equals(opportunity.getStatus());
            tally(byType, opportunity.getOpportunityType(), won);
            tally(bySegmentType, WinRateModel.key(segment, opportunity.getOpportunityType()), won);
        }

        double prior = DeliveryEconomics.WIN_RATE_PRIOR_STRENGTH;

        Map<String, Double> rateByType = new TreeMap<>();
        byType.forEach((type, counts) ->
                rateByType.put(type, shrink(counts[0], counts[1], globalRate, prior)));

        Map<String, Double> rateBySegmentType = new TreeMap<>();
        Map<String, Integer> sampleSizes = new TreeMap<>();
        bySegmentType.forEach((key, counts) -> {
            String type = key.substring(key.indexOf('|') + 1);
            double parent = rateByType.getOrDefault(type, globalRate);
            rateBySegmentType.put(key, shrink(counts[0], counts[1], parent, prior));
            sampleSizes.put(key, counts[1]);
        });

        return new WinRateModel(globalRate, rateByType, rateBySegmentType, sampleSizes,
                closedCount, wonCount);
    }

    /** Posterior mean of a Beta prior centred on {@code parentRate} with weight {@code prior}. */
    private static double shrink(int wins, int total, double parentRate, double prior) {
        return (wins + prior * parentRate) / (total + prior);
    }

    private static void tally(Map<String, int[]> counts, String key, boolean won) {
        int[] cell = counts.computeIfAbsent(key, k -> new int[2]);
        if (won) {
            cell[0]++;
        }
        cell[1]++;
    }

    public List<ForecastDto.WinRateRow> winRateRows() {
        WinRateModel model = winRates();
        Map<String, int[]> raw = new TreeMap<>();
        Map<String, Client> clientsById = clientsById();
        for (Opportunity opportunity : opportunities.findClosed()) {
            Client client = clientsById.get(opportunity.getClientId());
            String segment = client == null ? "Unknown" : client.getSegment();
            tally(raw, WinRateModel.key(segment, opportunity.getOpportunityType()),
                    "Won".equals(opportunity.getStatus()));
        }

        List<ForecastDto.WinRateRow> rows = new ArrayList<>();
        raw.forEach((key, counts) -> {
            int split = key.indexOf('|');
            String segment = key.substring(0, split);
            String type = key.substring(split + 1);
            rows.add(new ForecastDto.WinRateRow(
                    segment, type,
                    DeliveryEconomics.rate(model.rateFor(segment, type)),
                    counts[1] == 0 ? null : DeliveryEconomics.rate((double) counts[0] / counts[1]),
                    counts[1], counts[0]));
        });
        rows.sort(Comparator.comparing(ForecastDto.WinRateRow::opportunityType)
                .thenComparing(ForecastDto.WinRateRow::segment));
        return rows;
    }

    // ==================================================================================
    // Forecast rows
    // ==================================================================================

    public List<ForecastRow> forecastRows() {
        return forecastRows(Assumptions.baseline());
    }

    /**
     * Build the weighted forecast under a set of assumptions. This is the single place overrides
     * are applied; nothing downstream re-weights anything, so revenue, coverage and capacity can
     * never disagree about which scenario they are describing.
     */
    public List<ForecastRow> forecastRows(Assumptions assumptions) {
        WinRateModel winRates = winRates();
        List<ForecastRow> rows = new ArrayList<>();
        rows.addAll(pipelineForecastRows(winRates, assumptions));
        rows.addAll(whitespaceForecastRows(winRates, assumptions));
        return rows;
    }

    /** Open CRM opportunities, weighted by smoothed historical win rate. */
    public List<ForecastRow> pipelineForecastRows(WinRateModel winRates, Assumptions assumptions) {
        Map<String, Client> clientsById = clientsById();
        List<ForecastRow> rows = new ArrayList<>();

        for (Opportunity opportunity : opportunities.findOpenPipeline()) {
            Client client = clientsById.get(opportunity.getClientId());
            String segment = client == null ? "Unknown" : client.getSegment();
            String clientName = client == null ? opportunity.getClientId() : client.getName();

            double amount = opportunity.getAmountUsd().doubleValue() * assumptions.dealSizeMultiplier();
            // The smoothed historical rate, NOT opportunity.probability. Weighting off the
            // rep-entered CRM number is what made the coverage ratio ignore the what-if sliders.
            double winRate = clampRate(
                    winRates.rateFor(segment, opportunity.getOpportunityType())
                            * assumptions.winRateMultiplier());

            rows.add(new ForecastRow(
                    ForecastRow.SOURCE_PIPELINE,
                    opportunity.getId(),
                    opportunity.getClientId(),
                    clientName,
                    segment,
                    opportunity.getPractice(),
                    opportunity.getOpportunityType(),
                    opportunity.getQuarter(),
                    DeliveryEconomics.money(amount),
                    DeliveryEconomics.rate(winRate),
                    DeliveryEconomics.money(amount * winRate)));
        }
        return rows;
    }

    /**
     * Revenue-bearing signals turned into projected revenue. Discounted twice on purpose --
     * whitespace has not been qualified, so it gets a conversion haircut before the same win rate
     * an actual opportunity would receive.
     */
    public List<ForecastRow> whitespaceForecastRows(WinRateModel winRates, Assumptions assumptions) {
        List<ForecastRow> rows = new ArrayList<>();

        for (Signal signal : signalDetection.allSignals()) {
            if (!signal.carriesRevenue()) {
                continue;
            }
            String segment = signal.segment() == null ? "Unknown" : signal.segment();
            double amount = signal.estimatedValueUsd().doubleValue() * assumptions.dealSizeMultiplier();
            double winRate = clampRate(
                    winRates.rateFor(segment, signal.opportunityType()) * assumptions.winRateMultiplier());
            double weighted = amount * DeliveryEconomics.WHITESPACE_CONVERSION * winRate;

            rows.add(new ForecastRow(
                    ForecastRow.SOURCE_WHITESPACE,
                    signal.id(),
                    signal.clientId(),
                    signal.clientName(),
                    segment,
                    signal.practice(),
                    signal.opportunityType(),
                    signal.recommendedQuarter(),
                    DeliveryEconomics.money(amount),
                    DeliveryEconomics.rate(winRate),
                    DeliveryEconomics.money(weighted)));
        }
        return rows;
    }

    private static double clampRate(double rate) {
        return Math.max(0.01, Math.min(0.99, rate));
    }

    // ==================================================================================
    // Aggregations
    // ==================================================================================

    /**
     * @param dimension one of {@code quarter}, {@code client}, {@code segment}, {@code practice},
     *                  {@code opportunityType}
     */
    public List<ForecastDto.ForecastBucket> forecastBy(String dimension, List<ForecastRow> rows) {
        Map<String, List<ForecastRow>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();

        for (ForecastRow row : rows) {
            String key = keyFor(dimension, row);
            labels.putIfAbsent(key, labelFor(dimension, row));
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<ForecastDto.ForecastBucket> buckets = new ArrayList<>();
        grouped.forEach((key, group) -> buckets.add(new ForecastDto.ForecastBucket(
                key,
                labels.get(key),
                sumWeighted(group),
                sumGross(group),
                sumWeighted(group.stream().filter(ForecastRow::isWhitespace).toList()),
                group.size())));

        // Quarters read chronologically; everything else reads biggest-first.
        if ("quarter".equals(dimension)) {
            buckets.sort(Comparator.comparing(ForecastDto.ForecastBucket::key, Quarters.ORDER));
        } else {
            buckets.sort(Comparator.comparing(ForecastDto.ForecastBucket::weightedAmountUsd).reversed());
        }
        return buckets;
    }

    private static String keyFor(String dimension, ForecastRow row) {
        return switch (dimension == null ? "quarter" : dimension) {
            case "client" -> nullSafe(row.clientId());
            case "segment" -> nullSafe(row.segment());
            case "practice" -> nullSafe(row.practice());
            case "opportunityType", "type" -> nullSafe(row.opportunityType());
            case "source" -> nullSafe(row.source());
            default -> nullSafe(row.quarter());
        };
    }

    private static String labelFor(String dimension, ForecastRow row) {
        return "client".equals(dimension) ? nullSafe(row.clientName()) : keyFor(dimension, row);
    }

    /** Segment x opportunity-type grid. Empty cells are omitted rather than zero-filled. */
    public List<ForecastDto.HeatmapCell> opportunityHeatmap(List<ForecastRow> rows) {
        // Keyed on a record: segment and opportunity-type names both contain spaces, so any
        // string delimiter would have to be chosen for what it cannot collide with.
        record Cell(String segment, String opportunityType) {
        }

        Map<Cell, List<ForecastRow>> grouped = new LinkedHashMap<>();
        for (ForecastRow row : rows) {
            grouped.computeIfAbsent(new Cell(nullSafe(row.segment()), nullSafe(row.opportunityType())),
                    k -> new ArrayList<>()).add(row);
        }

        List<ForecastDto.HeatmapCell> cells = new ArrayList<>();
        grouped.forEach((key, group) -> {
            cells.add(new ForecastDto.HeatmapCell(
                    key.segment(), key.opportunityType(),
                    sumWeighted(group),
                    sumGross(group),
                    sumWeighted(group.stream().filter(ForecastRow::isWhitespace).toList()),
                    group.size()));
        });
        cells.sort(Comparator.comparing(ForecastDto.HeatmapCell::segment)
                .thenComparing(ForecastDto.HeatmapCell::opportunityType));
        return cells;
    }

    /**
     * Actuals against the weighted forecast, across the whole horizon.
     *
     * <p>Past quarters carry an actual and a {@code null} projection; future quarters the reverse;
     * the in-progress quarter carries both. There is no snapshot table, so there is no honest
     * retrospective projection to report -- see the class javadoc.
     */
    public List<ForecastDto.ActualVsProjectedRow> actualVsProjected(List<ForecastRow> rows) {
        Map<String, BigDecimal> actual = closedWonByQuarter();
        Map<String, BigDecimal> projected = weightedByQuarter(rows);
        String current = asOfProvider.currentQuarter();

        List<ForecastDto.ActualVsProjectedRow> out = new ArrayList<>();
        for (String quarter : horizonIncluding(actual.keySet(), projected.keySet())) {
            int position = Quarters.between(current, quarter);
            String phase = position < 0 ? "actual" : position == 0 ? "in-progress" : "forecast";

            BigDecimal actualUsd = position <= 0 ? actual.getOrDefault(quarter, zero()) : null;
            BigDecimal projectedUsd = position >= 0 ? projected.getOrDefault(quarter, zero()) : null;
            BigDecimal variance = (actualUsd != null && projectedUsd != null)
                    ? actualUsd.subtract(projectedUsd) : null;
            BigDecimal variancePct = (variance != null && projectedUsd.signum() != 0)
                    ? variance.divide(projectedUsd, 4, RoundingMode.HALF_UP) : null;

            out.add(new ForecastDto.ActualVsProjectedRow(
                    quarter, phase, actualUsd, projectedUsd, variance, variancePct));
        }
        return out;
    }

    /**
     * Pipeline coverage per quarter.
     *
     * <p>The denominator is <em>remaining</em> target -- target minus what has already closed. When
     * that remainder is at or below {@link DeliveryEconomics#COVERAGE_TARGET_MET_EPSILON_USD} the
     * ratio is {@code null} and {@code targetAlreadyMet} is set, instead of dividing by
     * near-zero and reporting a 5-figure multiple.
     */
    public List<ForecastDto.CoverageRatioRow> pipelineCoverageRatio(List<ForecastRow> rows,
                                                                   Assumptions assumptions) {
        Map<String, BigDecimal> targetByQuarter = targetsByQuarter(assumptions);
        Map<String, BigDecimal> wonByQuarter = closedWonByQuarter();
        Map<String, BigDecimal> pipelineByQuarter = weightedByQuarter(rows);
        String current = asOfProvider.currentQuarter();

        List<ForecastDto.CoverageRatioRow> out = new ArrayList<>();
        for (String quarter : horizonIncluding(targetByQuarter.keySet(), pipelineByQuarter.keySet())) {
            BigDecimal target = targetByQuarter.getOrDefault(quarter, zero());
            BigDecimal won = wonByQuarter.getOrDefault(quarter, zero());
            BigDecimal pipeline = pipelineByQuarter.getOrDefault(quarter, zero());
            BigDecimal remaining = target.subtract(won);

            boolean alreadyMet = remaining.compareTo(DeliveryEconomics.COVERAGE_TARGET_MET_EPSILON_USD) <= 0;
            BigDecimal ratio = alreadyMet ? null : pipeline.divide(remaining, 4, RoundingMode.HALF_UP);

            int position = Quarters.between(current, quarter);
            out.add(new ForecastDto.CoverageRatioRow(
                    quarter,
                    position < 0 ? "actual" : position == 0 ? "in-progress" : "forecast",
                    target, won, remaining, pipeline, ratio, alreadyMet));
        }
        return out;
    }

    /**
     * A single headline coverage number: total weighted pipeline over total remaining target.
     *
     * <p>Only forward quarters count. A quarter that has already closed cannot be covered by future
     * pipeline, so folding its shortfall into the denominator makes a forward-looking metric
     * quietly pessimistic -- eight historical quarters of small misses dragged the blended ratio
     * from 1.20x to 1.13x here before this was restricted. Quarters whose target is already met are
     * skipped for the same reason: there is nothing left to cover.
     *
     * <p>{@code null} when nothing in the forward horizon needs covering.
     */
    public BigDecimal blendedCoverageRatio(List<ForecastRow> rows, Assumptions assumptions) {
        BigDecimal pipeline = zero();
        BigDecimal remaining = zero();
        for (ForecastDto.CoverageRatioRow row : pipelineCoverageRatio(rows, assumptions)) {
            if (row.targetAlreadyMet() || !isForwardLooking(row)) {
                continue;
            }
            pipeline = pipeline.add(row.weightedPipelineUsd());
            remaining = remaining.add(row.remainingTargetUsd());
        }
        if (remaining.compareTo(DeliveryEconomics.COVERAGE_TARGET_MET_EPSILON_USD) <= 0) {
            return null;
        }
        return pipeline.divide(remaining, 4, RoundingMode.HALF_UP);
    }

    /** The current, in-progress quarter and everything after it. */
    public static boolean isForwardLooking(ForecastDto.CoverageRatioRow row) {
        return !"actual".equals(row.phase());
    }

    /** Actual / projected / target per quarter, with running totals for the trend chart. */
    public List<ForecastDto.TrendPoint> trendLines(List<ForecastRow> rows, Assumptions assumptions) {
        Map<String, BigDecimal> targetByQuarter = targetsByQuarter(assumptions);
        Map<String, BigDecimal> actual = closedWonByQuarter();
        Map<String, BigDecimal> projected = weightedByQuarter(rows);
        String current = asOfProvider.currentQuarter();

        BigDecimal runningActual = zero();
        BigDecimal runningProjected = zero();
        List<ForecastDto.TrendPoint> points = new ArrayList<>();

        for (String quarter : horizonIncluding(targetByQuarter.keySet(), actual.keySet(), projected.keySet())) {
            int position = Quarters.between(current, quarter);
            BigDecimal actualUsd = position <= 0 ? actual.getOrDefault(quarter, zero()) : null;
            BigDecimal projectedUsd = position >= 0 ? projected.getOrDefault(quarter, zero()) : null;

            if (actualUsd != null) {
                runningActual = runningActual.add(actualUsd);
            }
            if (projectedUsd != null) {
                runningProjected = runningProjected.add(projectedUsd);
            }

            points.add(new ForecastDto.TrendPoint(
                    quarter,
                    position < 0 ? "actual" : position == 0 ? "in-progress" : "forecast",
                    actualUsd, projectedUsd,
                    targetByQuarter.getOrDefault(quarter, zero()),
                    runningActual, runningProjected));
        }
        return points;
    }

    /** The KPI roll-up for a forecast-row list. */
    public ForecastDto.ForecastTotals totals(List<ForecastRow> rows) {
        List<ForecastRow> pipelineOnly = rows.stream().filter(r -> !r.isWhitespace()).toList();
        List<ForecastRow> whitespace = rows.stream().filter(ForecastRow::isWhitespace).toList();
        BigDecimal gross = sumGross(rows);
        BigDecimal weighted = sumWeighted(rows);

        return new ForecastDto.ForecastTotals(
                weighted,
                gross,
                sumWeighted(pipelineOnly),
                sumWeighted(whitespace),
                closedWonToDate(),
                gross.signum() == 0 ? null : weighted.divide(gross, 4, RoundingMode.HALF_UP),
                pipelineOnly.size(),
                whitespace.size(),
                asOfProvider.forecastQuarters());
    }

    // ==================================================================================
    // Shared reductions
    // ==================================================================================

    public Map<String, BigDecimal> targetsByQuarter(Assumptions assumptions) {
        double multiplier = assumptions.targetMultiplier();
        Map<String, BigDecimal> out = new TreeMap<>(Quarters.ORDER);
        for (Target target : targets.findAll()) {
            BigDecimal scaled = DeliveryEconomics.money(
                    target.getTargetAmountUsd().doubleValue() * multiplier);
            out.merge(target.getQuarter(), scaled, BigDecimal::add);
        }
        return out;
    }

    public Map<String, BigDecimal> closedWonByQuarter() {
        Map<String, BigDecimal> out = new TreeMap<>(Quarters.ORDER);
        for (Opportunity opportunity : opportunities.findWon()) {
            out.merge(opportunity.getQuarter(),
                    opportunity.getAmountUsd().setScale(DeliveryEconomics.MONEY_SCALE, RoundingMode.HALF_UP),
                    BigDecimal::add);
        }
        return out;
    }

    public BigDecimal closedWonToDate() {
        return closedWonByQuarter().values().stream().reduce(zero(), BigDecimal::add);
    }

    public static Map<String, BigDecimal> weightedByQuarter(List<ForecastRow> rows) {
        Map<String, BigDecimal> out = new TreeMap<>(Quarters.ORDER);
        for (ForecastRow row : rows) {
            out.merge(row.quarter(), row.weightedAmountUsd(), BigDecimal::add);
        }
        return out;
    }

    public static BigDecimal sumWeighted(List<ForecastRow> rows) {
        return rows.stream().map(ForecastRow::weightedAmountUsd).reduce(zero(), BigDecimal::add);
    }

    public static BigDecimal sumGross(List<ForecastRow> rows) {
        return rows.stream().map(ForecastRow::amountUsd).reduce(zero(), BigDecimal::add);
    }

    // ==================================================================================
    // Plumbing
    // ==================================================================================

    private Map<String, Client> clientsById() {
        Map<String, Client> map = new HashMap<>();
        for (Client client : clients.findAll()) {
            map.put(client.getId(), client);
        }
        return map;
    }

    /** The configured horizon, extended with any quarter the data actually mentions. */
    @SafeVarargs
    private List<String> horizonIncluding(Set<String>... extra) {
        Set<String> quarters = new LinkedHashSet<>(asOfProvider.horizon());
        for (Set<String> set : extra) {
            quarters.addAll(set);
        }
        List<String> sorted = new ArrayList<>(quarters);
        sorted.sort(Quarters.ORDER);
        return sorted;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(DeliveryEconomics.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String nullSafe(String value) {
        return value == null ? "Unknown" : value;
    }
}
