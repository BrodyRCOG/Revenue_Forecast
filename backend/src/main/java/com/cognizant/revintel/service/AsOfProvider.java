package com.cognizant.revintel.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Single source of "now" for the whole app.
 *
 * <p>Everything time-relative -- which quarters are history, which are forecast, how far an asset
 * is from end-of-support -- resolves through here, so a test can pin the date instead of going
 * flaky in the first week of a new quarter.
 *
 * <p>Defaults to today, matching the generator's default {@code POC_AS_OF_DATE}. If you generate
 * a dataset with a non-default as-of date, set {@code revintel.as-of-date} to the same value or
 * the two sides will disagree about which quarters are in the past.
 */
@Service
public class AsOfProvider {

    private final LocalDate asOf;
    private final int historicalQuarters;
    private final int forecastQuarters;

    public AsOfProvider(@Value("${revintel.as-of-date:}") String configuredAsOf,
                        @Value("${revintel.historical-quarters:8}") int historicalQuarters,
                        @Value("${revintel.forecast-quarters:4}") int forecastQuarters) {
        this.asOf = (configuredAsOf == null || configuredAsOf.isBlank())
                ? LocalDate.now()
                : LocalDate.parse(configuredAsOf.trim());
        this.historicalQuarters = historicalQuarters;
        this.forecastQuarters = forecastQuarters;
    }

    /**
     * Pin the date directly, for tests. A static factory rather than a second constructor so
     * Spring has exactly one candidate to autowire.
     */
    public static AsOfProvider pinnedTo(LocalDate asOf) {
        return new AsOfProvider(asOf.toString(), 8, 4);
    }

    public LocalDate asOf() {
        return asOf;
    }

    public String currentQuarter() {
        return Quarters.labelOf(asOf);
    }

    /** The completed quarters before the current one, oldest first. */
    public List<String> historicalQuarters() {
        return IntStream.rangeClosed(1, historicalQuarters)
                .map(n -> historicalQuarters - n + 1)
                .mapToObj(n -> Quarters.shift(currentQuarter(), -n))
                .toList();
    }

    /** The current (in-progress) quarter and the ones after it, oldest first. */
    public List<String> forecastQuarters() {
        return IntStream.range(0, forecastQuarters)
                .mapToObj(n -> Quarters.shift(currentQuarter(), n))
                .toList();
    }

    /** History followed by forecast -- the full x-axis of every trend chart. */
    public List<String> horizon() {
        return java.util.stream.Stream.concat(historicalQuarters().stream(), forecastQuarters().stream())
                .toList();
    }

    /** Months from the as-of date until {@code day}; negative when {@code day} is in the past. */
    public double monthsUntil(LocalDate day) {
        if (day == null) {
            return Double.NaN;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(asOf, day);
        return days / 30.44;
    }
}
