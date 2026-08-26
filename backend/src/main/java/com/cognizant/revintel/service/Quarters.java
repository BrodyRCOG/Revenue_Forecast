package com.cognizant.revintel.service;

import java.time.LocalDate;
import java.util.Comparator;

/**
 * Quarter-label arithmetic. Labels look like {@code 2026-Q3} and sort correctly as plain strings,
 * which is why they are stored as strings rather than a composite key.
 */
public final class Quarters {

    /** Sorts quarter labels chronologically. Identical to natural string order, but explicit. */
    public static final Comparator<String> ORDER = Comparator.comparingInt(Quarters::indexOf);

    private Quarters() {
    }

    public static String labelOf(LocalDate day) {
        return day.getYear() + "-Q" + ((day.getMonthValue() - 1) / 3 + 1);
    }

    /** Absolute quarter number, so labels can be added to and subtracted from. */
    public static int indexOf(String label) {
        int split = label.indexOf("-Q");
        int year = Integer.parseInt(label.substring(0, split));
        int quarter = Integer.parseInt(label.substring(split + 2));
        return year * 4 + (quarter - 1);
    }

    public static String fromIndex(int index) {
        return (index / 4) + "-Q" + (index % 4 + 1);
    }

    public static String shift(String label, int delta) {
        return fromIndex(indexOf(label) + delta);
    }

    public static LocalDate startOf(String label) {
        int index = indexOf(label);
        return LocalDate.of(index / 4, (index % 4) * 3 + 1, 1);
    }

    public static LocalDate endOf(String label) {
        return startOf(shift(label, 1)).minusDays(1);
    }

    /** Signed distance in quarters: {@code between("2026-Q1", "2026-Q3") == 2}. */
    public static int between(String from, String to) {
        return indexOf(to) - indexOf(from);
    }
}
