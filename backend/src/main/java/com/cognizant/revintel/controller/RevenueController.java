package com.cognizant.revintel.controller;

import com.cognizant.revintel.dto.NarrativeResponse;
import com.cognizant.revintel.dto.RevenueIntelligencePayload;
import com.cognizant.revintel.service.RevenueIntelligenceOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP in, DTO out. One call into the orchestrator per endpoint; no business logic here. */
@RestController
@RequestMapping("/api/revenue-intelligence")
public class RevenueController {

    private final RevenueIntelligenceOrchestrator orchestrator;

    public RevenueController(RevenueIntelligenceOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public RevenueIntelligencePayload revenueIntelligence() {
        return orchestrator.revenueIntelligence();
    }

    /**
     * Narrate one signal.
     *
     * <p>The request carries the signal's id and, for the frontend's convenience, the fields it
     * already has. Only the id is used: the signal is re-resolved server-side so the narration is
     * grounded in numbers this backend computed, not numbers a client posted. A stale or unknown id
     * gets a 404 rather than narration of whatever was in the body.
     */
    @PostMapping("/narrative/signal")
    public ResponseEntity<NarrativeResponse> explainSignal(@Valid @RequestBody SignalNarrativeRequest request) {
        NarrativeResponse response = orchestrator.explainSignal(request.signalId());
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    public record SignalNarrativeRequest(@NotBlank String signalId) {
    }
}
