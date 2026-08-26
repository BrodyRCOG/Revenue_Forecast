package com.cognizant.revintel.dto;

import java.util.List;

/**
 * A narration result, always carrying its own provenance.
 *
 * <p>{@code grounded} and {@code source} are not diagnostics -- they are part of the contract. The
 * UI shows them as a badge so a reader can always tell whether they are looking at generated prose
 * that passed the groundedness check, or a template rendered from the same numbers because the
 * check failed (or because no API key is configured).
 *
 * @param source          {@code llm} when generated text survived the groundedness check,
 *                        {@code template} when it did not or the LLM was never called
 * @param grounded        every number in {@code text} matched a number that was actually passed
 *                        into the prompt
 * @param rejectedNumbers numeric tokens the model produced that had no counterpart in the ground
 *                        truth; populated only when generated text was discarded
 * @param groundTruth     the numbers the narration was allowed to use, for auditing
 */
public record NarrativeResponse(
        String text,
        String source,
        boolean grounded,
        String reason,
        List<String> rejectedNumbers,
        List<String> groundTruth) {

    public static final String SOURCE_LLM = "llm";
    public static final String SOURCE_TEMPLATE = "template";

    public static NarrativeResponse fromLlm(String text, List<String> groundTruth) {
        return new NarrativeResponse(text, SOURCE_LLM, true,
                "Generated text passed the groundedness check.", List.of(), groundTruth);
    }

    public static NarrativeResponse fromTemplate(String text, String reason, List<String> groundTruth) {
        return new NarrativeResponse(text, SOURCE_TEMPLATE, false, reason, List.of(), groundTruth);
    }

    public static NarrativeResponse rejected(String templateText, String reason,
                                             List<String> rejectedNumbers, List<String> groundTruth) {
        return new NarrativeResponse(templateText, SOURCE_TEMPLATE, false, reason,
                rejectedNumbers, groundTruth);
    }
}
