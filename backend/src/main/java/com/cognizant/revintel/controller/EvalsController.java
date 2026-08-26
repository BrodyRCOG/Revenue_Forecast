package com.cognizant.revintel.controller;

import com.cognizant.revintel.dto.EvalsPayload;
import com.cognizant.revintel.service.EvalsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evals")
public class EvalsController {

    private final EvalsService evals;

    public EvalsController(EvalsService evals) {
        this.evals = evals;
    }

    @GetMapping
    public EvalsPayload evals() {
        return evals.evals();
    }
}
