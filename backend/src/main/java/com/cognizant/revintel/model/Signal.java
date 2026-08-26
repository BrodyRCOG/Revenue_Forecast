package com.cognizant.revintel.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Something worth acting on that is <em>not yet in the sales cycle</em>: an estate about to fall
 * out of support with nobody selling a refresh, a contract drifting towards a lapsed renewal, a
 * delivery group already past its utilisation ceiling.
 *
 * <p>Signals are produced by pure rule and join logic over the repositories. Nothing here is
 * modelled, inferred or generated -- which is the point: there is nothing for an LLM to
 * hallucinate. {@link #evidence()} carries the numeric facts behind the signal and doubles as the
 * ground-truth set for narration (spec section 1).
 */
public record Signal(
        String id,
        String type,
        String severity,
        String clientId,
        String clientName,
        String segment,
        String title,
        String detail,
        String recommendedAction,
        String opportunityType,
        String practice,
        String recommendedQuarter,
        BigDecimal estimatedValueUsd,
        Map<String, Object> evidence) {

    public static final String TYPE_LIFECYCLE = "lifecycle_risk";
    public static final String TYPE_CONTRACT = "contract_renewal_risk";
    public static final String TYPE_CAPACITY = "capacity_pressure";

    /** Only revenue-bearing signals become whitespace forecast rows. */
    public boolean carriesRevenue() {
        return estimatedValueUsd != null
                && estimatedValueUsd.signum() > 0
                && opportunityType != null
                && recommendedQuarter != null;
    }
}
