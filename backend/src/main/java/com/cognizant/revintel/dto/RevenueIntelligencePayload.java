package com.cognizant.revintel.dto;

import com.cognizant.revintel.model.Signal;

import java.util.List;

/** Everything the Revenue Intelligence tab renders, in one response. */
public record RevenueIntelligencePayload(
        String asOfDate,
        String currentQuarter,
        String datasetSource,
        List<ExecutiveDto.Kpi> kpis,
        ForecastDto.ForecastTotals totals,
        List<ForecastDto.TrendPoint> trend,
        List<ForecastDto.ActualVsProjectedRow> actualVsProjected,
        List<ForecastDto.ForecastBucket> byQuarter,
        List<ForecastDto.ForecastBucket> bySegment,
        List<ForecastDto.ForecastBucket> byPractice,
        List<ForecastDto.ForecastBucket> byOpportunityType,
        List<ForecastDto.ForecastBucket> topClients,
        List<ForecastDto.HeatmapCell> heatmap,
        List<ForecastDto.CoverageRatioRow> coverage,
        List<ForecastDto.WinRateRow> winRates,
        List<Signal> signals) {
}
