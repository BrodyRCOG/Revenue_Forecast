package com.cognizant.revintel.model;

import java.util.Map;

/**
 * Hierarchically smoothed historical win rates.
 *
 * <p>A raw group win rate is useless at POC data volumes: a (segment, opportunity-type) cell with
 * two closed deals and one win reads as 50%. So each level is pulled towards its parent by a fixed
 * number of pseudo-observations (Bayesian shrinkage with a Beta prior centred on the parent rate):
 *
 * <pre>
 *   global rate            <- all closed deals
 *   type rate              <- shrunk towards global
 *   (segment, type) rate   <- shrunk towards its type rate
 * </pre>
 *
 * <p>Thinly-observed cells therefore fall back gracefully instead of producing confident nonsense.
 *
 * @param sampleSizes closed-deal count per {@code segment|type} key, so the UI can show how much
 *                    evidence sits behind a rate
 */
public record WinRateModel(
        double globalRate,
        Map<String, Double> rateByType,
        Map<String, Double> rateBySegmentAndType,
        Map<String, Integer> sampleSizes,
        int closedCount,
        int wonCount) {

    public static String key(String segment, String opportunityType) {
        return segment + "|" + opportunityType;
    }

    /** The most specific smoothed rate available for this pair. */
    public double rateFor(String segment, String opportunityType) {
        Double specific = rateBySegmentAndType.get(key(segment, opportunityType));
        if (specific != null) {
            return specific;
        }
        Double byType = rateByType.get(opportunityType);
        return byType != null ? byType : globalRate;
    }

    public int sampleSizeFor(String segment, String opportunityType) {
        return sampleSizes.getOrDefault(key(segment, opportunityType), 0);
    }
}
