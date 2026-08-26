package com.cognizant.revintel.model;

import java.math.BigDecimal;

/**
 * The three what-if levers, expressed as fractional deltas ({@code 0.10} = +10%).
 *
 * <p>Overrides are applied inside {@code ForecastingService.forecastRows(Assumptions)}, i.e. at the
 * point the forecast rows are built -- not afterwards on an aggregate. That is deliberate: in the
 * reference build the coverage ratio computed weighted pipeline from the raw CRM probability
 * instead of the forecast rows, so moving a slider changed the headline number but not the
 * coverage ratio. Anything derived from the rows now moves together, capacity included.
 *
 * @param winRateDelta  scales the smoothed historical win rate
 * @param dealSizeDelta scales every deal's gross amount
 * @param targetDelta   scales quarterly revenue targets (the coverage-ratio denominator)
 */
public record Assumptions(BigDecimal winRateDelta, BigDecimal dealSizeDelta, BigDecimal targetDelta) {

    private static final Assumptions BASELINE =
            new Assumptions(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    /** Deltas are clamped to +/-100% so a slider cannot drive a negative pipeline. */
    private static final double LIMIT = 1.0;

    public Assumptions {
        winRateDelta = clamp(winRateDelta);
        dealSizeDelta = clamp(dealSizeDelta);
        targetDelta = clamp(targetDelta);
    }

    public static Assumptions baseline() {
        return BASELINE;
    }

    public static Assumptions of(Double winRateDelta, Double dealSizeDelta, Double targetDelta) {
        return new Assumptions(
                winRateDelta == null ? BigDecimal.ZERO : BigDecimal.valueOf(winRateDelta),
                dealSizeDelta == null ? BigDecimal.ZERO : BigDecimal.valueOf(dealSizeDelta),
                targetDelta == null ? BigDecimal.ZERO : BigDecimal.valueOf(targetDelta));
    }

    public boolean isBaseline() {
        return winRateDelta.signum() == 0 && dealSizeDelta.signum() == 0 && targetDelta.signum() == 0;
    }

    public double winRateMultiplier() {
        return 1.0 + winRateDelta.doubleValue();
    }

    public double dealSizeMultiplier() {
        return 1.0 + dealSizeDelta.doubleValue();
    }

    public double targetMultiplier() {
        return 1.0 + targetDelta.doubleValue();
    }

    private static BigDecimal clamp(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        double d = value.doubleValue();
        if (Double.isNaN(d)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.max(-LIMIT, Math.min(LIMIT, d)));
    }
}
