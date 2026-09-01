package com.cognizant.revintel.pipeline;

import com.cognizant.revintel.service.EvalsService;
import com.cognizant.revintel.service.RevenueIntelligenceOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-layer eval: JSON payload safety.
 *
 * <p>Regression test for "serialization errors on numeric edge cases". {@code NaN} and
 * {@code Infinity} are not valid JSON -- Jackson will happily write them and the browser's
 * {@code JSON.parse} will then reject the whole response, so one bad division takes down a page
 * that was otherwise fine. Every payload is serialised here and walked for them.
 *
 * <p>{@code null} is fine and expected: an undefined ratio should be {@code null}, not {@code 0}.
 * What is checked is that a {@code null} never turns up in a field the UI must have.
 */
@SpringBootTest
class JsonPayloadSafetyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RevenueIntelligenceOrchestrator orchestrator;

    @Autowired
    private EvalsService evals;

    @Test
    void everyPayloadSerialisesToValidJson() throws Exception {
        Map<String, Object> payloads = Map.of(
                "revenue-intelligence", orchestrator.revenueIntelligence(),
                "capacity-intelligence", orchestrator.capacityIntelligence(),
                "executive-summary", orchestrator.executiveSummary(),
                "manager-overview", orchestrator.managerOverview(),
                "data-sources", orchestrator.dataSources(),
                "evals", evals.evals());

        for (Map.Entry<String, Object> entry : payloads.entrySet()) {
            String json = MAPPER.writeValueAsString(entry.getValue());

            assertThat(json)
                    .as("%s must not contain non-finite numbers", entry.getKey())
                    .doesNotContain("NaN")
                    .doesNotContain("Infinity")
                    .doesNotContain("-Infinity");

            JsonNode reparsed = MAPPER.readTree(json);
            assertThat(reparsed.isObject())
                    .as("%s must round-trip through a strict JSON parse", entry.getKey())
                    .isTrue();
            assertThat(nonFinitePaths(reparsed))
                    .as("%s contained non-finite numeric nodes", entry.getKey())
                    .isEmpty();
        }
    }

    @Test
    void requiredIdentityFieldsAreNeverNull() throws Exception {
        JsonNode revenue = MAPPER.valueToTree(orchestrator.revenueIntelligence());

        assertThat(revenue.path("asOfDate").asText()).isNotBlank();
        assertThat(revenue.path("currentQuarter").asText()).matches("\\d{4}-Q[1-4]");
        assertThat(revenue.path("datasetSource").asText()).isIn("generated", "fallback");

        for (JsonNode bucket : revenue.path("byQuarter")) {
            assertThat(bucket.path("key").isNull()).isFalse();
            assertThat(bucket.path("weightedAmountUsd").isNull()).isFalse();
        }
        for (JsonNode signal : revenue.path("signals")) {
            assertThat(signal.path("id").asText()).isNotBlank();
            assertThat(signal.path("severity").asText()).isIn("Critical", "High", "Medium", "Low");
            assertThat(signal.path("title").asText()).isNotBlank();
        }
        for (JsonNode kpi : revenue.path("kpis")) {
            // value may legitimately be null (undefined ratio); the label never may.
            assertThat(kpi.path("label").asText()).isNotBlank();
            assertThat(kpi.path("unit").asText()).isIn("usd", "ratio", "percent", "count", "hours");
        }
    }

    @Test
    void undefinedRatiosAreNullRatherThanSentinelNumbers() throws Exception {
        JsonNode revenue = MAPPER.valueToTree(orchestrator.revenueIntelligence());
        for (JsonNode row : revenue.path("coverage")) {
            if (row.path("targetAlreadyMet").asBoolean()) {
                assertThat(row.path("coverageRatio").isNull())
                        .as("quarter %s: already-met target must serialise coverageRatio as null",
                                row.path("quarter").asText())
                        .isTrue();
            }
        }
    }

    /** Depth-first walk collecting the path of any numeric node that is not finite. */
    private static List<String> nonFinitePaths(JsonNode root) {
        List<String> bad = new ArrayList<>();
        Deque<Map.Entry<String, JsonNode>> stack = new ArrayDeque<>();
        stack.push(Map.entry("$", root));

        while (!stack.isEmpty()) {
            Map.Entry<String, JsonNode> current = stack.pop();
            JsonNode node = current.getValue();

            if (node.isNumber() && !Double.isFinite(node.asDouble())) {
                bad.add(current.getKey());
            }
            if (node.isObject()) {
                node.properties().forEach(field ->
                        stack.push(Map.entry(current.getKey() + "." + field.getKey(), field.getValue())));
            } else if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    stack.push(Map.entry(current.getKey() + "[" + i + "]", node.get(i)));
                }
            }
        }
        return bad;
    }
}
