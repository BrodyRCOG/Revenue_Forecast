package com.cognizant.revintel.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.cognizant.revintel.config.NarrativeProperties;
import com.cognizant.revintel.dto.NarrativeResponse;
import com.cognizant.revintel.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The one place in this application that calls a language model.
 *
 * <p>It is given numbers that have already been computed and asked for prose. It is never asked to
 * compute, estimate, rank or infer anything -- every figure in the prompt came out of
 * {@code ForecastingService}, {@code CapacityService} or {@code SignalDetectionService} first.
 *
 * <p>The output is then checked before anyone sees it:
 *
 * <pre>
 *   computed data ──┬──► prompt ──► generated text ──► GroundednessChecker.check()
 *                   │                                        │
 *                   │                                  grounded?   ungrounded?
 *                   └──► flatten() ─► ground truth        │            │
 *                                                         ▼            ▼
 *                                                   return text   discard, render
 *                                                                 template instead
 * </pre>
 *
 * <p>Three things guarantee a caller always gets usable prose: no API key configured skips the
 * model entirely; a failed groundedness check discards the generation; and any transport or API
 * error is caught. All three land on the same template path, and the response always says which
 * one happened.
 */
@Service
public class NarrativeService {

    private static final Logger log = LoggerFactory.getLogger(NarrativeService.class);

    private static final String SYSTEM_PROMPT = """
            You are writing short factual commentary for a revenue-intelligence dashboard used by \
            account and delivery leaders at a technology services firm whose clients are banks and \
            credit unions.

            Absolute rules:
            - Use ONLY the figures given to you. Do not compute, derive, estimate, round to a \
              different precision, annualise, total, or infer any number that is not listed.
            - Never introduce a number that is not in the supplied data. That includes percentages, \
              counts, dollar amounts, dates and durations.
            - If a figure you would want is not supplied, write around it rather than guessing.
            - No preamble, no headings, no bullet points, no markdown. Plain prose only.
            - Two to three sentences. Say what the situation is and what it implies for the next \
              quarter's plan.
            - Never claim a prediction is certain, and never invent a client name or product name.
            """;

    private final GroundednessChecker groundednessChecker;
    private final NarrativeProperties properties;
    private final AnthropicClient client;

    public NarrativeService(GroundednessChecker groundednessChecker, NarrativeProperties properties) {
        this.groundednessChecker = groundednessChecker;
        this.properties = properties;
        this.client = createClient(properties);
    }

    private static AnthropicClient createClient(NarrativeProperties properties) {
        if (!properties.llmEnabled()) {
            log.info("ANTHROPIC_API_KEY is not set -- narration will use templates only. "
                    + "This is a supported configuration; every narrative response reports "
                    + "source=template so the UI can say so.");
            return null;
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(properties.anthropicApiKey())
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .build();
    }

    public boolean llmEnabled() {
        return client != null;
    }

    // ==================================================================================
    // Narration entry points
    // ==================================================================================

    /** Explain one detected signal. Ground truth is the signal's own evidence map. */
    public NarrativeResponse explainSignal(Signal signal) {
        if (signal == null) {
            return NarrativeResponse.fromTemplate("No signal supplied.", "No signal supplied.", List.of());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("client", signal.clientName());
        data.put("segment", signal.segment());
        data.put("severity", signal.severity());
        data.put("signalType", signal.type());
        data.put("headline", signal.title());
        data.put("detail", signal.detail());
        data.put("recommendedAction", signal.recommendedAction());
        data.put("opportunityType", signal.opportunityType());
        data.put("recommendedQuarter", signal.recommendedQuarter());
        data.put("estimatedValueUsd", signal.estimatedValueUsd());
        data.put("evidence", signal.evidence());

        return narrate(data, signalTemplate(signal),
                "Explain this signal to the account team in two or three sentences.");
    }

    /** Narrate a set of already-computed headline metrics. */
    public NarrativeResponse narrateSummary(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return NarrativeResponse.fromTemplate("No metrics supplied.", "No metrics supplied.", List.of());
        }
        return narrate(metrics, summaryTemplate(metrics),
                "Summarise the state of the forecast in two or three sentences.");
    }

    // ==================================================================================
    // The generate -> check -> fall back flow
    // ==================================================================================

    private NarrativeResponse narrate(Map<String, Object> data, String template, String instruction) {
        GroundednessChecker.GroundTruth truth = groundednessChecker.flatten(data);

        if (client == null) {
            return NarrativeResponse.fromTemplate(template,
                    "No ANTHROPIC_API_KEY configured; rendered from a template over the same data.",
                    truth.display());
        }

        String generated;
        try {
            generated = callModel(data, instruction);
        } catch (RuntimeException e) {
            log.warn("Narration call failed ({}): falling back to template.", e.toString());
            return NarrativeResponse.fromTemplate(template,
                    "Narration call failed (" + e.getClass().getSimpleName() + "); rendered from a template.",
                    truth.display());
        }

        GroundednessChecker.Result check = groundednessChecker.check(generated, truth);
        if (check.grounded()) {
            return NarrativeResponse.fromLlm(generated.trim(), truth.display());
        }

        // The generated text asserted a number nobody computed. Discard the whole thing -- a
        // partially-correct narrative is worse than a plain one, because it reads as authoritative.
        log.warn("Discarding ungrounded narration; unsupported numbers: {}", check.rejected());
        return NarrativeResponse.rejected(template,
                "Generated text contained " + check.rejected().size()
                        + " number(s) not present in the computed data, so it was discarded.",
                check.rejected(), truth.display());
    }

    private String callModel(Map<String, Object> data, String instruction) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens().longValue())
                .system(SYSTEM_PROMPT)
                .outputConfig(OutputConfig.builder().effort(effort()).build())
                .addUserMessage(instruction + "\n\nHere is every figure you may use:\n" + render(data))
                .build();

        Message response = client.messages().create(params);
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private OutputConfig.Effort effort() {
        return switch (properties.effort().toLowerCase()) {
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.LOW;
        };
    }

    /** Flat {@code key: value} rendering, so the prompt contains no numbers the data does not. */
    static String render(Object node) {
        StringBuilder out = new StringBuilder();
        renderInto(node, "", out);
        return out.toString();
    }

    private static void renderInto(Object node, String prefix, StringBuilder out) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> renderInto(value, prefix.isEmpty() ? String.valueOf(key)
                    : prefix + "." + key, out));
        } else if (node instanceof Iterable<?> items) {
            int index = 0;
            for (Object item : items) {
                renderInto(item, prefix + "[" + index++ + "]", out);
            }
        } else {
            out.append("- ").append(prefix).append(": ").append(stringify(node)).append('\n');
        }
    }

    private static String stringify(Object value) {
        return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString()
                : String.valueOf(value);
    }

    // ==================================================================================
    // Template fallbacks -- same data, no model
    // ==================================================================================

    public static String signalTemplate(Signal signal) {
        StringBuilder text = new StringBuilder();
        text.append(signal.title()).append(". ").append(signal.detail());
        if (signal.estimatedValueUsd() != null && signal.recommendedQuarter() != null) {
            text.append(String.format(" Estimated %s value is %s, best positioned for %s.",
                    signal.opportunityType().toLowerCase(),
                    formatUsd(signal.estimatedValueUsd()),
                    signal.recommendedQuarter()));
        }
        if (signal.recommendedAction() != null) {
            text.append(' ').append(signal.recommendedAction());
        }
        return text.toString();
    }

    static String summaryTemplate(Map<String, Object> metrics) {
        StringBuilder text = new StringBuilder("Forecast summary: ");
        String joined = metrics.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .filter(e -> !(e.getValue() instanceof Map) && !(e.getValue() instanceof Iterable))
                .map(e -> humanise(e.getKey()) + " " + stringify(e.getValue()))
                .collect(Collectors.joining(", "));
        text.append(joined.isEmpty() ? "no metrics available" : joined).append('.');
        return text.toString();
    }

    private static String humanise(String key) {
        String spaced = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace("Usd", "(USD)")
                .toLowerCase();
        return spaced;
    }

    private static String formatUsd(BigDecimal value) {
        double amount = value.doubleValue();
        if (amount >= 1_000_000) {
            return String.format("$%.1fM", amount / 1_000_000);
        }
        return String.format("$%.0fk", amount / 1_000);
    }
}
