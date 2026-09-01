package com.cognizant.revintel.controller;

import com.cognizant.revintel.dto.ManagerOverviewPayload;
import com.cognizant.revintel.service.RevenueIntelligenceOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP in, DTO out. One call into the orchestrator; no business logic here. */
@RestController
@RequestMapping("/api/manager-overview")
public class ManagerController {

    private final RevenueIntelligenceOrchestrator orchestrator;

    public ManagerController(RevenueIntelligenceOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public ManagerOverviewPayload managerOverview() {
        return orchestrator.managerOverview();
    }
}
