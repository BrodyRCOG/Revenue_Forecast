package com.cognizant.revintel.controller;

import com.cognizant.revintel.dto.ExecutiveDto;
import com.cognizant.revintel.model.Assumptions;
import com.cognizant.revintel.service.WhatIfService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/what-if")
public class WhatIfController {

    private final WhatIfService whatIf;

    public WhatIfController(WhatIfService whatIf) {
        this.whatIf = whatIf;
    }

    @PostMapping
    public ExecutiveDto.WhatIfScenario evaluate(@Valid @RequestBody WhatIfRequest request) {
        return whatIf.evaluate(Assumptions.of(
                request.winRateDelta(), request.dealSizeDelta(), request.targetDelta()));
    }

    /** Fractional deltas: {@code 0.10} means +10%. Null means no change on that lever. */
    public record WhatIfRequest(
            @DecimalMin("-1.0") @DecimalMax("1.0") Double winRateDelta,
            @DecimalMin("-1.0") @DecimalMax("1.0") Double dealSizeDelta,
            @DecimalMin("-1.0") @DecimalMax("1.0") Double targetDelta) {
    }
}
