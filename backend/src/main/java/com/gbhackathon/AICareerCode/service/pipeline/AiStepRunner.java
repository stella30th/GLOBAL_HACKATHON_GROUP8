package com.gbhackathon.AICareerCode.service.pipeline;

import com.gbhackathon.AICareerCode.service.ai.AiInvalidResponseException;
import com.gbhackathon.AICareerCode.service.ai.GeminiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * Runs one pipeline step: prompt the model, validate the answer against the step's own rules, and
 * on failure ask it to correct that specific answer - a bounded number of times.
 *
 * <p>The repair loop is what makes "no written-in-advance content" survivable. Without it, the
 * only responses to an invalid answer are to accept it, which puts a broken plan in front of a
 * student, or to fail the request outright, which fails far more often than it needs to because
 * models get one field wrong and the rest right. With it, the model is told exactly which rule it
 * broke and given the chance to fix it; if it still cannot, the request fails loudly and nothing
 * is substituted.
 *
 * <p>The bound matters as much as the loop. Each attempt is a real API call against a quota the
 * free tier measures in requests per day, so an unbounded retry would turn one bad answer into an
 * outage for every later user.
 */
@Component
public class AiStepRunner {

    private static final Logger log = LoggerFactory.getLogger(AiStepRunner.class);

    private final GeminiClient gemini;

    @Value("${ai.pipeline.max-repair-attempts:2}")
    private int maxRepairAttempts;

    public AiStepRunner(GeminiClient gemini) {
        this.gemini = gemini;
    }

    /**
     * @param stepName  used in logs and in the plan's provenance
     * @param prompt    the full prompt for the first attempt
     * @param type      the shape the answer must deserialize into
     * @param validator returns the rules the answer broke; an empty list means it is acceptable
     */
    public <T> StepResult<T> run(String stepName, String prompt, Class<T> type,
                                 Function<T, List<String>> validator) {
        String currentPrompt = prompt;
        String lastProblem = null;
        int attempts = 0;

        for (int attempt = 0; attempt <= Math.max(0, maxRepairAttempts); attempt++) {
            attempts = attempt;
            try {
                GeminiClient.AiResult<T> result = gemini.generateJson(currentPrompt, type);
                List<String> problems = validator.apply(result.value());
                if (problems.isEmpty()) {
                    if (attempt > 0) {
                        log.info("Step '{}' passed validation after {} repair attempt(s)", stepName, attempt);
                    }
                    return new StepResult<>(result.value(), result.model(), attempt);
                }
                lastProblem = String.join("\n- ", problems);
                log.info("Step '{}' failed validation (attempt {}): {}", stepName, attempt + 1, lastProblem);
                currentPrompt = withCorrection(prompt, lastProblem);
            } catch (AiInvalidResponseException e) {
                lastProblem = e.getMessage();
                log.info("Step '{}' returned unusable JSON (attempt {}): {}", stepName, attempt + 1, lastProblem);
                currentPrompt = withCorrection(prompt, lastProblem);
            }
            // AiUnavailableException is not caught: no model answered, so repeating the prompt
            // against the same two models cannot help and would only spend more quota.
        }

        throw new AiInvalidResponseException("The AI could not produce a valid result for '" + stepName
                + "' after " + (attempts + 1) + " attempts. Last problem: " + lastProblem);
    }

    /** What came back, which model produced it, and how many corrections it took. */
    public record StepResult<T>(T value, String model, int repairAttempts) {}

    private String withCorrection(String originalPrompt, String problems) {
        return originalPrompt + """

                ---
                YOUR PREVIOUS ANSWER WAS REJECTED. It broke these rules:
                - %s

                Produce the whole answer again, corrected. Do not explain the correction, do not
                apologise, and do not drop content that was fine - return the complete JSON document
                in the same shape, with those specific problems fixed. If a rule cannot be satisfied
                because the information genuinely is not available to you, use the field provided
                for saying so rather than inventing something that passes the check.
                """.formatted(problems);
    }
}
