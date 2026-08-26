package com.cognizant.revintel.dto;

import java.util.List;

/**
 * Combined status of all three eval layers (spec section 6).
 *
 * @param status {@code all_clear} | {@code warnings} | {@code failing} | {@code not_run}
 * @param note   how these numbers were produced, so a reader knows how fresh they are
 */
public record EvalsPayload(
        String status,
        boolean allClear,
        int totalChecks,
        int passed,
        int failed,
        int warnings,
        List<Layer> layers,
        String note) {

    /**
     * One eval layer.
     *
     * @param layer  {@code data} | {@code pipeline} | {@code narrative}
     * @param source where the result was read from, and in which language it runs
     */
    public record Layer(
            String layer,
            String label,
            String language,
            String source,
            String status,
            int total,
            int passed,
            int failed,
            int warnings,
            String generatedAt,
            String message,
            List<Check> checks) {
    }

    public record Check(
            String name,
            String severity,
            boolean passed,
            String message) {
    }
}
