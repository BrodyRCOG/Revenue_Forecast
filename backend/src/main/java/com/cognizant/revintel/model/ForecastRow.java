package com.cognizant.revintel.model;

import java.math.BigDecimal;

/**
 * One unit of forecast revenue, already weighted. Every revenue aggregation in the app -- by
 * quarter, by client, by segment, by practice, the heatmap, the coverage ratio -- is a fold over
 * a list of these, and {@code CapacityService} converts the same list into demand hours.
 *
 * <p>Producing the rows once and passing them to both services is what makes revenue and capacity
 * numbers provably consistent, and what makes a what-if override reach both (spec 4.1).
 *
 * @param source        {@code pipeline} for an open CRM opportunity, {@code whitespace} for a
 *                      detected signal that is not in the sales cycle yet
 * @param referenceId   the opportunity id or signal id this row came from
 * @param amountUsd     gross value, after any deal-size override
 * @param winRate       the probability actually applied -- the smoothed historical rate after any
 *                      win-rate override, never the rep-entered CRM probability
 * @param weightedAmountUsd {@code amountUsd * winRate}, and for whitespace also times the
 *                      whitespace conversion assumption
 */
public record ForecastRow(
        String source,
        String referenceId,
        String clientId,
        String clientName,
        String segment,
        String practice,
        String opportunityType,
        String quarter,
        BigDecimal amountUsd,
        BigDecimal winRate,
        BigDecimal weightedAmountUsd) {

    public static final String SOURCE_PIPELINE = "pipeline";
    public static final String SOURCE_WHITESPACE = "whitespace";

    public boolean isWhitespace() {
        return SOURCE_WHITESPACE.equals(source);
    }
}
