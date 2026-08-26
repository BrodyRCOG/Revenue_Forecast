package com.cognizant.revintel.narrative;

import com.cognizant.revintel.config.NarrativeProperties;
import com.cognizant.revintel.dto.NarrativeResponse;
import com.cognizant.revintel.model.Signal;
import com.cognizant.revintel.service.GroundednessChecker;
import com.cognizant.revintel.service.NarrativeService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Narrative-layer eval: template fallback.
 *
 * <p>With no API key the service must still produce usable prose over the real numbers, and must
 * say so. This is the configuration the POC ships in by default, so it is the path most likely to
 * be seen -- it gets the same scrutiny as the LLM path.
 */
class NarrativeFallbackTest {

    private static final NarrativeProperties NO_KEY =
            new NarrativeProperties("", "claude-opus-5", 1024, "low", 20);

    private final NarrativeService service =
            new NarrativeService(new GroundednessChecker(), NO_KEY);

    private static Signal lifecycleSignal() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("assetRecords", 14);
        evidence.put("units", 212);
        evidence.put("annualSupportCostUsd", new BigDecimal("480000.00"));
        evidence.put("monthsPastEndOfSupport", new BigDecimal("18.0000"));

        return new Signal(
                "SIG-EOL-CLI-0001-SWITCHING", Signal.TYPE_LIFECYCLE, "Critical",
                "CLI-0001", "Cedar Valley Community Bank", "Community Bank",
                "Cedar Valley Community Bank switching estate already 18 months past end of support",
                "14 switching asset record(s) covering 212 unit(s) from Cisco, already 18 months "
                        + "past end of support. No open refresh or modernization opportunity exists "
                        + "for this client.",
                "Open a refresh conversation before the support lapse forces an emergency buy.",
                "Infrastructure Refresh", "Infrastructure", "2026-Q4",
                new BigDecimal("1234567.89"), evidence);
    }

    @Test
    void withoutAnApiKeyTheLlmIsNeverCalled() {
        assertThat(service.llmEnabled()).isFalse();
    }

    @Test
    void signalNarrationFallsBackToATemplateAndSaysSo() {
        NarrativeResponse response = service.explainSignal(lifecycleSignal());

        assertThat(response.source()).isEqualTo(NarrativeResponse.SOURCE_TEMPLATE);
        assertThat(response.grounded()).isFalse();
        assertThat(response.reason()).contains("ANTHROPIC_API_KEY");
        assertThat(response.rejectedNumbers()).isEmpty();
    }

    @Test
    void theTemplateStillCarriesTheRealNumbers() {
        NarrativeResponse response = service.explainSignal(lifecycleSignal());

        assertThat(response.text())
                .contains("Cedar Valley Community Bank")
                .contains("212 unit")
                .contains("2026-Q4")
                .contains("$1.2M");
        assertThat(response.text()).doesNotContain("null");
    }

    @Test
    void groundTruthIsReportedForAuditingEvenOnTheTemplatePath() {
        NarrativeResponse response = service.explainSignal(lifecycleSignal());

        assertThat(response.groundTruth())
                .as("a reviewer must be able to see which numbers narration was allowed to use")
                .isNotEmpty()
                .anySatisfy(value -> assertThat(value).contains("212"));
    }

    @Test
    void summaryNarrationFallsBackToATemplate() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("weightedPipelineUsd", new BigDecimal("4820000.00"));
        metrics.put("blendedCoverageRatio", new BigDecimal("1.1800"));
        metrics.put("recommendedFteHires", 3);

        NarrativeResponse response = service.narrateSummary(metrics);

        assertThat(response.source()).isEqualTo(NarrativeResponse.SOURCE_TEMPLATE);
        assertThat(response.text())
                .contains("4820000")
                .contains("1.18")
                .contains("3");
    }

    @Test
    void missingInputIsHandledWithoutThrowing() {
        assertThat(service.explainSignal(null).text()).isEqualTo("No signal supplied.");
        assertThat(service.narrateSummary(null).text()).isEqualTo("No metrics supplied.");
        assertThat(service.narrateSummary(Map.of()).text()).isEqualTo("No metrics supplied.");
    }

    @Test
    void aSignalWithNoRevenueStillNarratesCleanly() {
        Signal capacitySignal = new Signal(
                "SIG-CAP-WF-0004", Signal.TYPE_CAPACITY, "Critical",
                null, null, null,
                "Security Engineering is running at 96% utilisation",
                "8 FTE plus 1 contractor(s) at 96% against a 85% target.",
                "Add capacity before committing new work to this group.",
                null, "Security", null, null,
                Map.of("headcount", 8, "currentUtilization", new BigDecimal("0.9600")));

        NarrativeResponse response = service.explainSignal(capacitySignal);

        assertThat(response.text()).contains("Security Engineering").doesNotContain("null");
        assertThat(response.source()).isEqualTo(NarrativeResponse.SOURCE_TEMPLATE);
    }

    /**
     * The core contract, verified without an API key: text whose numbers do not trace back to the
     * computed data is discarded and the template is served instead, with the offending tokens
     * reported.
     */
    @Test
    void ungroundedTextWouldBeDiscardedInFavourOfTheTemplate() {
        GroundednessChecker checker = new GroundednessChecker();
        Signal signal = lifecycleSignal();

        Map<String, Object> promptData = new LinkedHashMap<>();
        promptData.put("estimatedValueUsd", signal.estimatedValueUsd());
        promptData.put("evidence", signal.evidence());

        String hallucinated = "This is a $3.7M opportunity across 47 sites.";
        GroundednessChecker.Result result = checker.check(hallucinated, promptData);

        assertThat(result.grounded()).isFalse();
        NarrativeResponse response = NarrativeResponse.rejected(
                NarrativeService.signalTemplate(signal),
                "Generated text contained " + result.rejected().size() + " number(s) not present.",
                result.rejected(), checker.flatten(promptData).display());

        assertThat(response.source()).isEqualTo(NarrativeResponse.SOURCE_TEMPLATE);
        assertThat(response.grounded()).isFalse();
        assertThat(response.rejectedNumbers()).isNotEmpty();
        assertThat(response.text()).doesNotContain("3.7M").doesNotContain("47 sites");
    }
}
