package com.cognizant.revintel.service;

import com.cognizant.revintel.config.DataBootstrap;
import com.cognizant.revintel.dto.CapacityDto;
import com.cognizant.revintel.dto.CapacityIntelligencePayload;
import com.cognizant.revintel.dto.DataSourcesPayload;
import com.cognizant.revintel.dto.ExecutiveDto;
import com.cognizant.revintel.dto.ForecastDto;
import com.cognizant.revintel.dto.NarrativeResponse;
import com.cognizant.revintel.dto.RevenueIntelligencePayload;
import com.cognizant.revintel.entity.OemModel;
import com.cognizant.revintel.entity.Workforce;
import com.cognizant.revintel.model.Assumptions;
import com.cognizant.revintel.model.ForecastRow;
import com.cognizant.revintel.model.Signal;
import com.cognizant.revintel.repository.DataCatalogRepository;
import com.cognizant.revintel.repository.OemModelRepository;
import com.cognizant.revintel.repository.WorkforceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the payload each frontend page consumes. Does no computation of its own -- if a number
 * is being worked out here rather than in a service, it is in the wrong place.
 *
 * <p>This is also the only class that needs to change when a new service is wired in, which is the
 * point of having it.
 *
 * <p>Note {@link #revenueIntelligence()}: the forecast rows are computed <b>once</b> and passed to
 * every aggregation and to {@link CapacityService}. That single decision is what guarantees the
 * revenue figures and the capacity figures on screen are describing the same forecast.
 */
@Service
public class RevenueIntelligenceOrchestrator {

    private static final int TOP_CLIENTS = 12;

    private final ForecastingService forecasting;
    private final SignalDetectionService signalDetection;
    private final WhatIfService whatIf;
    private final NarrativeService narrative;
    private final WorkforceRepository workforce;
    private final DataCatalogRepository catalog;
    private final OemModelRepository oemModels;
    private final AsOfProvider asOfProvider;
    private final DataBootstrap dataBootstrap;

    public RevenueIntelligenceOrchestrator(ForecastingService forecasting,
                                           SignalDetectionService signalDetection,
                                           WhatIfService whatIf,
                                           NarrativeService narrative,
                                           WorkforceRepository workforce,
                                           DataCatalogRepository catalog,
                                           OemModelRepository oemModels,
                                           AsOfProvider asOfProvider,
                                           DataBootstrap dataBootstrap) {
        this.forecasting = forecasting;
        this.signalDetection = signalDetection;
        this.whatIf = whatIf;
        this.narrative = narrative;
        this.workforce = workforce;
        this.catalog = catalog;
        this.oemModels = oemModels;
        this.asOfProvider = asOfProvider;
        this.dataBootstrap = dataBootstrap;
    }

    // ==================================================================================
    // Revenue Intelligence
    // ==================================================================================

    @Transactional(readOnly = true)
    public RevenueIntelligencePayload revenueIntelligence() {
        Assumptions baseline = Assumptions.baseline();
        List<ForecastRow> rows = forecasting.forecastRows(baseline);

        ForecastDto.ForecastTotals totals = forecasting.totals(rows);
        List<ForecastDto.CoverageRatioRow> coverage = forecasting.pipelineCoverageRatio(rows, baseline);
        List<Signal> signals = signalDetection.allSignals();

        List<ForecastDto.ForecastBucket> clients = forecasting.forecastBy("client", rows);

        return new RevenueIntelligencePayload(
                asOfProvider.asOf().toString(),
                asOfProvider.currentQuarter(),
                datasetSource(),
                revenueKpis(totals, coverage, signals),
                totals,
                forecasting.trendLines(rows, baseline),
                forecasting.actualVsProjected(rows),
                forecasting.forecastBy("quarter", rows),
                forecasting.forecastBy("segment", rows),
                forecasting.forecastBy("practice", rows),
                forecasting.forecastBy("opportunityType", rows),
                clients.subList(0, Math.min(TOP_CLIENTS, clients.size())),
                forecasting.opportunityHeatmap(rows),
                coverage,
                forecasting.winRateRows(),
                signals);
    }

    private List<ExecutiveDto.Kpi> revenueKpis(ForecastDto.ForecastTotals totals,
                                               List<ForecastDto.CoverageRatioRow> coverage,
                                               List<Signal> signals) {
        ForecastDto.CoverageRatioRow current = coverage.stream()
                .filter(row -> "in-progress".equals(row.phase()))
                .findFirst()
                .orElse(null);

        long revenueSignals = signals.stream().filter(Signal::carriesRevenue).count();
        long criticalSignals = signals.stream().filter(s -> "Critical".equals(s.severity())).count();

        List<ExecutiveDto.Kpi> kpis = new ArrayList<>();
        kpis.add(new ExecutiveDto.Kpi("weightedPipeline", "Weighted pipeline",
                totals.weightedPipelineUsd(), "usd",
                "Open pipeline plus whitespace, weighted by smoothed historical win rates"));
        kpis.add(new ExecutiveDto.Kpi("grossPipeline", "Gross pipeline",
                totals.grossPipelineUsd(), "usd",
                totals.openOpportunityCount() + " open opportunities before weighting"));
        kpis.add(new ExecutiveDto.Kpi("whitespace", "Whitespace revenue",
                totals.whitespaceWeightedUsd(), "usd",
                revenueSignals + " signals not yet in the sales cycle"));
        kpis.add(new ExecutiveDto.Kpi("closedWon", "Closed won to date",
                totals.closedWonToDateUsd(), "usd", "Across the full history in the dataset"));
        kpis.add(new ExecutiveDto.Kpi("blendedWinRate", "Blended win rate",
                totals.blendedWinRate(), "ratio",
                "Weighted pipeline as a share of gross pipeline"));
        kpis.add(new ExecutiveDto.Kpi("coverageThisQuarter", "Coverage, current quarter",
                current == null ? null : current.coverageRatio(), "ratio",
                current == null ? "No target for the current quarter"
                        : current.targetAlreadyMet()
                        ? "Target already met -- coverage is undefined, not infinite"
                        : "Weighted pipeline over remaining target"));
        kpis.add(new ExecutiveDto.Kpi("criticalSignals", "Critical signals",
                BigDecimal.valueOf(criticalSignals), "count",
                signals.size() + " signals detected in total"));
        return kpis;
    }

    // ==================================================================================
    // Capacity Intelligence
    // ==================================================================================

    @Transactional(readOnly = true)
    public CapacityIntelligencePayload capacityIntelligence() {
        // Forecast rows computed here, once, and handed to CapacityService -- the whole reason
        // CapacityService takes them as a constructor argument instead of fetching its own.
        List<ForecastRow> rows = forecasting.forecastRows(Assumptions.baseline());
        List<Workforce> workforceRows = workforce.findAll();
        CapacityService capacity = new CapacityService(rows, workforceRows);

        CapacityDto.CapacitySummary summary = capacity.summary();
        List<CapacityDto.HiringRecommendation> hiring = capacity.hiringRecommendations();

        return new CapacityIntelligencePayload(
                asOfProvider.asOf().toString(),
                asOfProvider.forecastQuarters(),
                capacityKpis(summary),
                summary,
                capacity.skillDemandForecast(),
                capacity.sustainableCapacity(),
                capacity.utilizationProjection(),
                hiring,
                signalDetection.capacityPressureSignals());
    }

    private List<ExecutiveDto.Kpi> capacityKpis(CapacityDto.CapacitySummary summary) {
        return List.of(
                new ExecutiveDto.Kpi("headcount", "Delivery headcount",
                        BigDecimal.valueOf(summary.totalHeadcount()), "count",
                        summary.totalContractors() + " contractors alongside"),
                new ExecutiveDto.Kpi("demandHours", "Forecast demand",
                        summary.totalForecastDemandHours(), "hours",
                        "Across " + summary.forecastQuarters().size() + " forecast quarters"),
                new ExecutiveDto.Kpi("sustainableHours", "Sustainable capacity",
                        summary.totalSustainableHours(), "hours",
                        "Headcount at target utilisation over the same window"),
                new ExecutiveDto.Kpi("projectedUtilisation", "Projected utilisation",
                        summary.fleetProjectedUtilization(), "ratio",
                        "Fleet-wide, against sustainable capacity"),
                new ExecutiveDto.Kpi("fteHires", "Recommended hires",
                        BigDecimal.valueOf(summary.recommendedFteHires()), "count",
                        summary.recommendedContractors() + " contractors for peak cover"),
                new ExecutiveDto.Kpi("groupsOverCapacity", "Groups over capacity",
                        BigDecimal.valueOf(summary.groupsOverCapacity()), "count",
                        "Resource-group quarters projected above 100% utilisation"));
    }

    // ==================================================================================
    // Executive Insights
    // ==================================================================================

    @Transactional(readOnly = true)
    public ExecutiveDto.ExecutiveSummary executiveSummary() {
        return executiveSummary(Assumptions.baseline());
    }

    @Transactional(readOnly = true)
    public ExecutiveDto.ExecutiveSummary executiveSummary(Assumptions assumptions) {
        List<Workforce> workforceRows = workforce.findAll();
        ExecutiveDto.WhatIfScenario scenario = whatIf.evaluate(assumptions);

        // Same rows, same assumptions, for the charts on this page.
        List<ForecastRow> rows = forecasting.forecastRows(assumptions);
        CapacityService capacity = new CapacityService(rows, workforceRows);
        List<CapacityDto.HiringRecommendation> hiring = capacity.hiringRecommendations();

        ExecutiveDto.WhatIfMetrics metrics = scenario.scenario();

        return new ExecutiveDto.ExecutiveSummary(
                asOfProvider.asOf().toString(),
                asOfProvider.currentQuarter(),
                executiveKpis(metrics),
                forecasting.trendLines(rows, assumptions),
                metrics.coverageByQuarter(),
                hiring.stream()
                        .filter(row -> row.recommendedFteHires() > 0 || row.recommendedContractors() > 0)
                        .sorted(Comparator.comparing(CapacityDto.HiringRecommendation::recommendedFteHires)
                                .reversed())
                        .limit(5)
                        .toList(),
                capacity.summary(),
                scenario,
                narrateExecutive(metrics));
    }

    private List<ExecutiveDto.Kpi> executiveKpis(ExecutiveDto.WhatIfMetrics metrics) {
        return List.of(
                new ExecutiveDto.Kpi("projectedRevenue", "Projected total revenue",
                        metrics.projectedTotalRevenueUsd(), "usd",
                        "Closed won to date plus weighted pipeline"),
                new ExecutiveDto.Kpi("weightedPipeline", "Weighted pipeline",
                        metrics.weightedPipelineUsd(), "usd",
                        "Includes whitespace, discounted for conversion"),
                new ExecutiveDto.Kpi("remainingTarget", "Remaining target",
                        metrics.remainingTargetUsd(), "usd",
                        "Summed only over quarters not already met"),
                new ExecutiveDto.Kpi("coverage", "Blended coverage",
                        metrics.blendedCoverageRatio(), "ratio",
                        metrics.blendedCoverageRatio() == null
                                ? "Every quarter's target is already met"
                                : "Weighted pipeline over remaining target"),
                new ExecutiveDto.Kpi("fteHires", "Recommended hires",
                        BigDecimal.valueOf(metrics.recommendedFteHires()), "count",
                        "Against " + metrics.currentHeadcount() + " current FTE"),
                new ExecutiveDto.Kpi("hiringGap", "Hiring gap",
                        metrics.hiringGapAsShareOfHeadcount(), "ratio",
                        "Recommended hires as a share of current headcount"));
    }

    /**
     * Narration for the executive page. Only already-computed figures go into the prompt, and the
     * response carries its own {@code grounded}/{@code source} flags.
     */
    private NarrativeResponse narrateExecutive(ExecutiveDto.WhatIfMetrics metrics) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("weightedPipelineUsd", metrics.weightedPipelineUsd());
        data.put("grossPipelineUsd", metrics.grossPipelineUsd());
        data.put("whitespaceWeightedUsd", metrics.whitespaceWeightedUsd());
        data.put("closedWonToDateUsd", metrics.closedWonToDateUsd());
        data.put("projectedTotalRevenueUsd", metrics.projectedTotalRevenueUsd());
        data.put("totalTargetUsd", metrics.totalTargetUsd());
        data.put("remainingTargetUsd", metrics.remainingTargetUsd());
        data.put("blendedCoverageRatio", metrics.blendedCoverageRatio());
        data.put("forecastDemandHours", metrics.forecastDemandHours());
        data.put("currentHeadcount", metrics.currentHeadcount());
        data.put("recommendedFteHires", metrics.recommendedFteHires());
        data.put("recommendedContractors", metrics.recommendedContractors());
        return narrative.narrateSummary(data);
    }

    // ==================================================================================
    // Data Sources
    // ==================================================================================

    @Transactional(readOnly = true)
    public DataSourcesPayload dataSources() {
        List<DataSourcesPayload.RealAnchor> anchors = oemModels.findByRealAnchorTrue().stream()
                .sorted(Comparator.comparing(OemModel::getOem).thenComparing(OemModel::getModelName))
                .map(model -> new DataSourcesPayload.RealAnchor(
                        model.getId(),
                        model.getOem(),
                        model.getModelName(),
                        model.getCategory(),
                        model.getEndOfSale() == null ? null : model.getEndOfSale().toString(),
                        model.getEndOfSupport() == null ? null : model.getEndOfSupport().toString(),
                        model.getSourceConfidence(),
                        model.getSourceUrl()))
                .toList();

        return new DataSourcesPayload(
                asOfProvider.asOf().toString(),
                datasetSource(),
                catalog.totalRows(),
                catalog.catalog().size(),
                anchors.size(),
                oemModels.count(),
                catalog.catalog(),
                anchors,
                "All client, opportunity, contract, billing and workforce data is synthetic. "
                        + "Only the OEM lifecycle anchors listed here are real, and each carries the "
                        + "source it came from. Every other OEM model is a procedural variant of one "
                        + "of those anchors' lifecycle spans and is labelled source_confidence="
                        + "\"synthetic\".");
    }

    // ==================================================================================
    // Narration passthrough
    // ==================================================================================

    /** Narrate one signal by id. Returns {@code null} when the id does not match a live signal. */
    @Transactional(readOnly = true)
    public NarrativeResponse explainSignal(String signalId) {
        return signalDetection.allSignals().stream()
                .filter(signal -> signal.id().equals(signalId))
                .findFirst()
                .map(narrative::explainSignal)
                .orElse(null);
    }

    public NarrativeResponse explainSignal(Signal signal) {
        return narrative.explainSignal(signal);
    }

    private String datasetSource() {
        return dataBootstrap.isUsingFallbackDataset() ? "fallback" : "generated";
    }
}
