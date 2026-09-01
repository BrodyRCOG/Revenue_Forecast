package com.cognizant.revintel.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether generated prose is allowed to reach the user.
 *
 * <p>The rule is narrow on purpose: <b>the narration may not contain a number that was not handed
 * to it.</b> Nothing here judges whether the prose is well written, on-message or even true -- only
 * whether every quantity in it traces back to a value the deterministic layer computed.
 *
 * <ol>
 *   <li>{@link #flatten} walks the exact data structure that went into the prompt and collects
 *       every numeric value, including numbers embedded in string values, as ground truth.</li>
 *   <li>{@link #check} extracts every numeric token from the generated text.</li>
 *   <li>A token is accepted if it matches a ground-truth value under any plausible rendering --
 *       raw, rounded to 0/1/2 decimals, scaled to thousands / millions / billions, or expressed as
 *       a percentage -- or if its magnitude (after a {@code k}/{@code M}/{@code bn} suffix) lands
 *       within a small relative tolerance of a ground-truth value.</li>
 *   <li>Anything left over is an invented number, and the caller discards the whole output.</li>
 * </ol>
 *
 * <p>Identifiers are masked before extraction rather than treated as claims: {@code 2026-Q4},
 * {@code OPP-00123} and ISO dates are labels, and failing narration for repeating one back would
 * make the check useless without making it stricter.
 */
@Component
public class GroundednessChecker {

    /**
     * A number, optionally with a magnitude suffix or percent sign.
     *
     * <p>The trailing guard is {@code (?!\.?\d)(?![A-Za-z])} rather than the more obvious
     * {@code (?![\w.])}. A number at the end of a sentence is followed by a full stop, and
     * {@code (?![\w.])} rejects that whole match and then backtracks into matching fragments of
     * it: "$1,234,568." came out as the two tokens "1" and "234", and "$5M." matched nothing at
     * all -- so a hallucinated figure could slip through simply by ending the sentence.
     * {@code (?!\.?\d)} still refuses to stop mid-decimal or mid-thousands-group.
     *
     * <p>The thousands-grouped alternative carries an optional {@code (?:\.\d+)?} decimal tail.
     * Without it, a value written with <em>both</em> separators and cents -- "$1,039,153.85", the
     * exact figure the deterministic layer computed -- tokenised as the two fragments "1,039" and
     * "153.85": the first happened to match the thousands rendering, the second matched nothing, and
     * the whole (correct) narration was discarded as ungrounded. Because {@code money()} emits two
     * decimals, most refresh and contract figures land in exactly this shape, so the checker was
     * silently rejecting the model whenever it quoted one back verbatim. Guarded by
     * {@code GroundednessCheckerTest.acceptsThousandsSeparatorsWithDecimals}.
     */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\w.])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)\\s*"
                    + "(%|percent|bn|billion|million|thousand|[kKmMbB])?(?!\\.?\\d)(?![A-Za-z])");

    /** Identifiers that look numeric but assert nothing: quarter labels, entity ids, ISO dates. */
    private static final List<Pattern> IDENTIFIER_MASKS = List.of(
            Pattern.compile("\\b\\d{4}-Q[1-4]\\b"),
            Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
            Pattern.compile("\\b[A-Z]{2,4}(?:-[A-Z0-9&]+)+\\b"));

    /**
     * Relative tolerance when comparing a suffix-expanded magnitude to a ground-truth value.
     *
     * <p>Deliberately tight. This track exists only to catch a rendering the literal track did not
     * enumerate; it is not a fuzzy-match allowance. At 5% it accepted "215 units" against a ground
     * truth of 212 -- exactly the kind of near-miss the checker exists to catch.
     */
    private static final double MAGNITUDE_TOLERANCE = 0.005;

    /** Absolute tolerance when comparing a literal token to a pre-rounded rendering. */
    private static final double LITERAL_TOLERANCE = 0.005;

    /**
     * Ground truth for one narration: every acceptable literal rendering, plus the raw values for
     * magnitude comparison.
     *
     * @param display human-readable list of the numbers narration was allowed to use; surfaced on
     *                the API response so a reviewer can audit a rejection
     */
    public record GroundTruth(Set<Double> literalRenderings, List<Double> rawValues, List<String> display) {

        public boolean isEmpty() {
            return rawValues.isEmpty();
        }
    }

    /**
     * @param grounded  no extracted number was left unmatched
     * @param rejected  the numeric tokens that had no counterpart in ground truth
     * @param extracted every numeric token found, matched or not
     */
    public record Result(boolean grounded, List<String> rejected, List<String> extracted) {
    }

    // ==================================================================================
    // Ground truth
    // ==================================================================================

    /**
     * Collect every numeric value reachable from {@code data}. Recurses through maps, collections
     * and arrays, and also mines numbers out of string values -- a signal's {@code detail}
     * sentence already contains figures like "$480k", and the model is entitled to repeat them.
     */
    public GroundTruth flatten(Object data) {
        List<Double> raw = new ArrayList<>();
        List<String> display = new ArrayList<>();
        collect(data, raw, display);

        Set<Double> renderings = new LinkedHashSet<>();
        for (double value : raw) {
            addRenderings(renderings, value);
        }
        return new GroundTruth(renderings, List.copyOf(raw), List.copyOf(display));
    }

    private void collect(Object node, List<Double> raw, List<String> display) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, raw, display));
        } else if (node instanceof Collection<?> collection) {
            collection.forEach(v -> collect(v, raw, display));
        } else if (node instanceof Object[] array) {
            for (Object item : array) {
                collect(item, raw, display);
            }
        } else if (node instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value)) {
                raw.add(value);
                display.add(node instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value));
            }
        } else if (node instanceof Boolean) {
            // Booleans carry no quantity.
        } else {
            // A string may still contain figures the narration is allowed to reuse.
            String text = node.toString();
            for (Extracted extracted : extractNumbers(text)) {
                raw.add(extracted.magnitude());
                raw.add(extracted.literal());
                display.add(extracted.token());
            }
        }
    }

    /** Every literal form a reader might reasonably see this value written as. */
    private static void addRenderings(Set<Double> out, double value) {
        // Raw, thousands, millions, billions, and as a percentage.
        double[] scales = {1.0, 1e-3, 1e-6, 1e-9, 100.0};
        for (double scale : scales) {
            double scaled = value * scale;
            if (!Double.isFinite(scaled)) {
                continue;
            }
            out.add(scaled);
            for (int decimals = 0; decimals <= 3; decimals++) {
                addRounded(out, scaled, decimals, value);
                addRounded(out, Math.abs(scaled), decimals, value);
            }
        }
    }

    /**
     * Adds a rounded rendering, but never lets a large value round down to zero and thereby make a
     * bare "0" acceptable. A ground truth of $265,000 has a billions rendering of 0.000265, and
     * rounding that to whole numbers would otherwise whitelist "0".
     */
    private static void addRounded(Set<Double> out, double scaled, int decimals, double original) {
        double rounded = round(scaled, decimals);
        if (rounded == 0.0 && original != 0.0) {
            return;
        }
        out.add(rounded);
    }

    private static double round(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    // ==================================================================================
    // Checking
    // ==================================================================================

    /** Convenience overload: flatten the prompt data and check in one call. */
    public Result check(String text, Object promptData) {
        return check(text, flatten(promptData));
    }

    public Result check(String text, GroundTruth truth) {
        if (text == null || text.isBlank()) {
            return new Result(false, List.of("<empty response>"), List.of());
        }

        List<String> extracted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (Extracted candidate : extractNumbers(mask(text))) {
            extracted.add(candidate.token());
            if (!matches(candidate, truth)) {
                rejected.add(candidate.token());
            }
        }
        return new Result(rejected.isEmpty(), List.copyOf(rejected), List.copyOf(extracted));
    }

    private static boolean matches(Extracted candidate, GroundTruth truth) {
        for (double rendering : truth.literalRenderings()) {
            if (Math.abs(rendering - candidate.literal()) <= LITERAL_TOLERANCE) {
                return true;
            }
        }
        // Fall back to comparing real magnitudes, so an unusual format ("$1.2 million") is judged
        // on what it claims rather than on how it is spelled.
        for (double value : truth.rawValues()) {
            double tolerance = Math.max(0.5, Math.abs(value) * MAGNITUDE_TOLERANCE);
            if (Math.abs(value - candidate.magnitude()) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    private static String mask(String text) {
        String masked = text;
        for (Pattern pattern : IDENTIFIER_MASKS) {
            masked = pattern.matcher(masked).replaceAll(" ");
        }
        return masked;
    }

    static List<Extracted> extractNumbers(String text) {
        List<Extracted> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            double literal;
            try {
                literal = Double.parseDouble(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String suffix = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase();
            double magnitude = literal * multiplierFor(suffix);
            out.add(new Extracted(matcher.group().trim(), literal, magnitude));
        }
        return out;
    }

    private static double multiplierFor(String suffix) {
        return switch (suffix) {
            case "k", "thousand" -> 1_000d;
            case "m", "million" -> 1_000_000d;
            case "b", "bn", "billion" -> 1_000_000_000d;
            // A percentage asserts the literal number; the /100 form is already a rendering.
            default -> 1d;
        };
    }

    /**
     * @param literal   the number exactly as written ({@code 1.2} in "$1.2M")
     * @param magnitude what it claims once the suffix is applied ({@code 1200000})
     */
    record Extracted(String token, double literal, double magnitude) {
    }
}
