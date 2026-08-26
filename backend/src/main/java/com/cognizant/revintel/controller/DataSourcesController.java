package com.cognizant.revintel.controller;

import com.cognizant.revintel.dto.DataSourcesPayload;
import com.cognizant.revintel.service.RevenueIntelligenceOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data-sources")
public class DataSourcesController {

    private final RevenueIntelligenceOrchestrator orchestrator;

    public DataSourcesController(RevenueIntelligenceOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public DataSourcesPayload dataSources() {
        return orchestrator.dataSources();
    }
}
