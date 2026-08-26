package com.cognizant.revintel.controller;

import com.cognizant.revintel.dto.ExecutiveDto;
import com.cognizant.revintel.model.Assumptions;
import com.cognizant.revintel.service.RevenueIntelligenceOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/executive")
public class ExecutiveController {

    private final RevenueIntelligenceOrchestrator orchestrator;

    public ExecutiveController(RevenueIntelligenceOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * The executive page. Optional deltas let the page load a scenario directly instead of
     * fetching baseline and then re-posting to the what-if endpoint; omitted deltas mean baseline.
     * {@link Assumptions} clamps the values, so out-of-range input is bounded rather than rejected.
     */
    @GetMapping("/summary")
    public ExecutiveDto.ExecutiveSummary summary(
            @RequestParam(required = false) Double winRateDelta,
            @RequestParam(required = false) Double dealSizeDelta,
            @RequestParam(required = false) Double targetDelta) {
        return orchestrator.executiveSummary(Assumptions.of(winRateDelta, dealSizeDelta, targetDelta));
    }
}
