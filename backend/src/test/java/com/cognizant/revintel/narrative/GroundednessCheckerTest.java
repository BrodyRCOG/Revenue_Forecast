package com.cognizant.revintel.narrative;

import com.cognizant.revintel.service.GroundednessChecker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Narrative-layer eval: the groundedness check itself.
 *
 * <p>Adversarial by design. The checker is the only thing standing between a fluent, confident,
 * invented number and a dashboard that looks authoritative, so these cases are written to break it:
 * numbers that are close but wrong, numbers hidden in different formats, and legitimate numbers
 * dressed up in ways the naive matcher would reject.
 */
class GroundednessCheckerTest {

    private final GroundednessChecker checker = new GroundednessChecker();

    private static Map<String, Object> data() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("client", "Cedar Valley Community Bank");
        data.put("estimatedValueUsd", new BigDecimal("1234567.89"));
        data.put("assetRecords", 14);
        data.put("units", 212);
        data.put("winRate", new BigDecimal("0.4235"));
        data.put("recommendedQuarter", "2026-Q4");
        data.put("annualSupportCostUsd", new BigDecimal("480000.00"));
        return data;
    }

    // -- the cases that matter: invented numbers -------------------------------------

    @Test
    void rejectsAnInventedDollarAmount() {
        GroundednessChecker.Result result = checker.check(
                "Cedar Valley Community Bank has a $3.7M refresh opportunity across 212 units.", data());

        assertThat(result.grounded()).isFalse();
        assertThat(result.rejected()).anySatisfy(token -> assertThat(token).contains("3.7"));
    }

    @Test
    void rejectsAnInventedCount() {
        GroundednessChecker.Result result = checker.check(
                "There are 47 asset records at risk across 212 units.", data());
        assertThat(result.grounded()).isFalse();
        assertThat(result.rejected()).anySatisfy(token -> assertThat(token).contains("47"));
    }

    @Test
    void rejectsAPlausibleButUnsuppliedDerivation() {
        // 212 units / 14 records is a real ratio, but nobody computed it -- the model did.
        GroundednessChecker.Result result = checker.check(
                "That averages 15.1 units per asset record.", data());
        assertThat(result.grounded()).isFalse();
    }

    @Test
    void rejectsANumberThatIsCloseButNotTheOne() {
        GroundednessChecker.Result result = checker.check(
                "The estate covers 215 units.", data());
        assertThat(result.grounded()).isFalse();
        assertThat(result.rejected()).contains("215");
    }

    @Test
    void rejectsEmptyOrMissingText() {
        assertThat(checker.check("", data()).grounded()).isFalse();
        assertThat(checker.check(null, data()).grounded()).isFalse();
        assertThat(checker.check("   ", data()).rejected()).contains("<empty response>");
    }

    // -- legitimate renderings the checker must not reject ----------------------------

    @Test
    void acceptsExactValues() {
        GroundednessChecker.Result result = checker.check(
                "14 asset records cover 212 units.", data());
        assertThat(result.grounded()).isTrue();
        assertThat(result.extracted()).contains("14", "212");
    }

    @Test
    void acceptsAbbreviatedMagnitudes() {
        assertThat(checker.check("An estimated $1.2M refresh.", data()).grounded()).isTrue();
        assertThat(checker.check("An estimated $1.23M refresh.", data()).grounded()).isTrue();
        assertThat(checker.check("Around $480k of annual support spend.", data()).grounded()).isTrue();
    }

    @Test
    void acceptsSpelledOutMagnitudes() {
        assertThat(checker.check("Roughly 1.2 million dollars of opportunity.", data()).grounded())
                .isTrue();
    }

    @Test
    void acceptsAPercentageRenderingOfARatio() {
        assertThat(checker.check("The historical win rate is 42%.", data()).grounded()).isTrue();
        assertThat(checker.check("The historical win rate is 42.4%.", data()).grounded()).isTrue();
        assertThat(checker.check("The historical win rate is 0.42.", data()).grounded()).isTrue();
    }

    @Test
    void acceptsThousandsSeparators() {
        assertThat(checker.check("Estimated value is $1,234,568.", data()).grounded()).isTrue();
    }

    @Test
    void doesNotTreatIdentifiersAsClaims() {
        // Quarter labels, entity ids and ISO dates are labels, not assertions about quantity.
        GroundednessChecker.Result result = checker.check(
                "Position this for 2026-Q4; see OPP-00123 and the 2027-01-12 support date.", data());
        assertThat(result.grounded()).isTrue();
    }

    @Test
    void acceptsNumbersThatOnlyAppearInsideSuppliedStrings() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("detail", "22 switches across 3 sites, $41,800 of annual support.");
        assertThat(checker.check("There are 22 switches across 3 sites.", data).grounded()).isTrue();
        assertThat(checker.check("There are 26 switches.", data).grounded()).isFalse();
    }

    @Test
    void textWithNoNumbersIsTriviallyGrounded() {
        GroundednessChecker.Result result = checker.check(
                "This estate is out of support and nobody is selling a refresh.", data());
        assertThat(result.grounded()).isTrue();
        assertThat(result.extracted()).isEmpty();
    }

    // -- flattening -------------------------------------------------------------------

    @Test
    void flattenWalksNestedMapsAndCollections() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("evidence", Map.of("units", 212));
        nested.put("quarters", List.of(Map.of("weightedUsd", new BigDecimal("500000"))));

        GroundednessChecker.GroundTruth truth = checker.flatten(nested);
        assertThat(truth.isEmpty()).isFalse();
        assertThat(checker.check("212 units and $500k weighted.", truth).grounded()).isTrue();
        assertThat(checker.check("900 units.", truth).grounded()).isFalse();
    }

    @Test
    void flattenIgnoresBooleansAndNulls() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("autoRenew", true);
        data.put("missing", null);
        data.put("arrUsd", new BigDecimal("265000"));

        GroundednessChecker.GroundTruth truth = checker.flatten(data);
        // "1" must not become acceptable just because a boolean was present.
        assertThat(checker.check("There is 1 contract.", truth).grounded()).isFalse();
        assertThat(checker.check("ARR is $265k.", truth).grounded()).isTrue();
    }

    @Test
    void emptyGroundTruthRejectsAnyNumber() {
        GroundednessChecker.GroundTruth truth = checker.flatten(Map.of());
        assertThat(truth.isEmpty()).isTrue();
        assertThat(checker.check("Revenue is $5M.", truth).grounded()).isFalse();
        assertThat(checker.check("No numbers here at all.", truth).grounded()).isTrue();
    }
}
