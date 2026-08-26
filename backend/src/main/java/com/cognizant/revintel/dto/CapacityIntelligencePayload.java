package com.cognizant.revintel.dto;

import com.cognizant.revintel.model.Signal;

import java.util.List;

/** Everything the Capacity Intelligence tab renders, in one response. */
public record CapacityIntelligencePayload(
        String asOfDate,
        List<String> forecastQuarters,
        List<ExecutiveDto.Kpi> kpis,
        CapacityDto.CapacitySummary summary,
        List<CapacityDto.SkillDemandRow> skillDemand,
        List<CapacityDto.CapacityRow> capacity,
        List<CapacityDto.UtilizationProjectionRow> utilizationProjection,
        List<CapacityDto.HiringRecommendation> hiringRecommendations,
        List<Signal> capacitySignals) {
}
