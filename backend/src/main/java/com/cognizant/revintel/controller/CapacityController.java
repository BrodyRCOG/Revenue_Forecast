package com.cognizant.revintel.controller;

import com.cognizant.revintel.dto.CapacityIntelligencePayload;
import com.cognizant.revintel.service.RevenueIntelligenceOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capacity-intelligence")
public class CapacityController {

    private final RevenueIntelligenceOrchestrator orchestrator;

    public CapacityController(RevenueIntelligenceOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public CapacityIntelligencePayload capacityIntelligence() {
        return orchestrator.capacityIntelligence();
    }
}
