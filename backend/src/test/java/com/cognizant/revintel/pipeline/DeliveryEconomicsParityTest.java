package com.cognizant.revintel.pipeline;

import com.cognizant.revintel.service.DeliveryEconomics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-layer eval: the Java and Python economic constants still agree.
 *
 * <p>The generator sizes workforce headcount off the demand these constants imply; the Java
 * capacity service converts forecast dollars into demand hours with the same constants. Nothing at
 * runtime notices if the two drift -- the numbers stay plausible-looking and quietly stop meaning
 * the same thing, which is how the "hire 5-6x your bench" bug survived review the first time.
 *
 * <p>So this test parses {@code data-tools/reference_data.py} and compares it to
 * {@link DeliveryEconomics} value by value. Editing one side without the other fails the build.
 *
 * <p>Skipped rather than failed when {@code reference_data.py} is not on disk, so the backend can
 * still be built and tested on its own.
 */
class DeliveryEconomicsParityTest {

    private static final List<String> CANDIDATES = List.of(
            "../data-tools/reference_data.py",
            "data-tools/reference_data.py");

    private static String source;

    @BeforeAll
    static void loadPythonSource() throws IOException {
        Optional<Path> found = CANDIDATES.stream().map(Path::of).filter(Files::isRegularFile).findFirst();
        source = found.isPresent() ? Files.readString(found.get()) : null;
    }

    static boolean pythonSourceAvailable() {
        return source != null;
    }

    @Test
    @EnabledIf("pythonSourceAvailable")
    void laborContentMatches() {
        assertMapMatches("LABOR_CONTENT", DeliveryEconomics.LABOR_CONTENT);
    }

    @Test
    @EnabledIf("pythonSourceAvailable")
    void deliveryRatesMatch() {
        assertMapMatches("DELIVERY_RATE_USD_PER_HOUR", DeliveryEconomics.DELIVERY_RATE_USD_PER_HOUR);
    }

    @Test
    @EnabledIf("pythonSourceAvailable")
    void annualCapacityHoursMatch() {
        assertMapMatches("ANNUAL_CAPACITY_HOURS", DeliveryEconomics.ANNUAL_CAPACITY_HOURS);
    }

    @Test
    @EnabledIf("pythonSourceAvailable")
    void scalarConstantsMatch() {
        assertScalar("TARGET_UTILIZATION", DeliveryEconomics.TARGET_UTILIZATION);
        assertScalar("OVER_UTILIZATION_THRESHOLD", DeliveryEconomics.OVER_UTILIZATION_THRESHOLD);
        assertScalar("WHITESPACE_CONVERSION", DeliveryEconomics.WHITESPACE_CONVERSION);
        assertScalar("WIN_RATE_PRIOR_STRENGTH", DeliveryEconomics.WIN_RATE_PRIOR_STRENGTH);
        assertScalar("COVERAGE_TARGET_MET_EPSILON_USD",
                DeliveryEconomics.COVERAGE_TARGET_MET_EPSILON_USD.doubleValue());
    }

    @Test
    @EnabledIf("pythonSourceAvailable")
    void resourceMixMatches() {
        String block = blockFor("RESOURCE_MIX");
        DeliveryEconomics.RESOURCE_MIX.forEach((opportunityType, mix) -> {
            String subBlock = nestedBlock(block, opportunityType);
            Map<String, Double> pythonMix = parseEntries(subBlock);
            assertThat(pythonMix)
                    .as("RESOURCE_MIX row for %s", opportunityType)
                    .containsExactlyInAnyOrderEntriesOf(mix);
        });
    }

    @Test
    @EnabledIf("pythonSourceAvailable")
    void taxonomiesMatch() {
        assertThat(parseStringList("OPPORTUNITY_TYPES"))
                .containsExactlyInAnyOrderElementsOf(DeliveryEconomics.OPPORTUNITY_TYPES);
        assertThat(parseStringList("PRACTICES"))
                .containsExactlyInAnyOrderElementsOf(DeliveryEconomics.PRACTICES);
        assertThat(parseStringList("RESOURCE_GROUPS"))
                .containsExactlyInAnyOrderElementsOf(DeliveryEconomics.RESOURCE_GROUPS);
    }

    // -- parsing -----------------------------------------------------------------------

    private static void assertMapMatches(String name, Map<String, Double> javaValues) {
        assertThat(parseEntries(blockFor(name)))
                .as("%s must match DeliveryEconomics", name)
                .containsExactlyInAnyOrderEntriesOf(javaValues);
    }

    private static void assertScalar(String name, double javaValue) {
        Matcher matcher = Pattern.compile("^" + name + "\\s*=\\s*([0-9_.]+)", Pattern.MULTILINE)
                .matcher(source);
        assertThat(matcher.find()).as("%s not found in reference_data.py", name).isTrue();
        double pythonValue = Double.parseDouble(matcher.group(1).replace("_", ""));
        assertThat(pythonValue).as("%s", name).isEqualTo(javaValue);
    }

    /** The text between a top-level {@code NAME ... = {} and its matching closing brace. */
    private static String blockFor(String name) {
        Matcher matcher = Pattern.compile("^" + name + "[^=\\n]*=\\s*\\{", Pattern.MULTILINE).matcher(source);
        assertThat(matcher.find()).as("%s not found in reference_data.py", name).isTrue();

        int start = matcher.end() - 1;
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start + 1, i);
                }
            }
        }
        throw new AssertionError("Unbalanced braces reading " + name);
    }

    /** The dict literal keyed by {@code key} inside an already-extracted block. */
    private static String nestedBlock(String block, String key) {
        Matcher matcher = Pattern.compile(Pattern.quote("\"" + key + "\"") + "\\s*:\\s*\\{").matcher(block);
        assertThat(matcher.find()).as("nested key %s not found", key).isTrue();

        int start = matcher.end() - 1;
        int depth = 0;
        for (int i = start; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return block.substring(start + 1, i);
                }
            }
        }
        throw new AssertionError("Unbalanced braces reading nested key " + key);
    }

    /** {@code "key": 0.35,} pairs at any nesting level of the supplied text. */
    private static Map<String, Double> parseEntries(String block) {
        Map<String, Double> out = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*([0-9_.]+)").matcher(block);
        while (matcher.find()) {
            out.put(matcher.group(1), Double.parseDouble(matcher.group(2).replace("_", "")));
        }
        assertThat(out).as("no entries parsed").isNotEmpty();
        return out;
    }

    private static List<String> parseStringList(String name) {
        Matcher matcher = Pattern.compile("^" + name + "[^=\\n]*=\\s*\\[(.*?)]",
                Pattern.MULTILINE | Pattern.DOTALL).matcher(source);
        assertThat(matcher.find()).as("%s not found in reference_data.py", name).isTrue();

        List<String> values = new java.util.ArrayList<>();
        Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(matcher.group(1));
        while (item.find()) {
            values.add(item.group(1));
        }
        return values;
    }
}
