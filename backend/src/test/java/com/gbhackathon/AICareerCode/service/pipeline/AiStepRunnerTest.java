package com.gbhackathon.AICareerCode.service.pipeline;

import com.gbhackathon.AICareerCode.service.ai.AiInvalidResponseException;
import com.gbhackathon.AICareerCode.service.ai.AiUnavailableException;
import com.gbhackathon.AICareerCode.service.ai.GeminiClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repair loop is what makes "nothing written in advance" survivable: a model that gets one
 * field wrong is told which rule it broke and given a bounded number of chances to fix it, and if
 * it still cannot, the request fails rather than having content substituted.
 *
 * <p>The stub here replaces the model, not the logic under test. It never reaches the network.
 */
class AiStepRunnerTest {

    /** What a step is asked to produce, reduced to the one field these tests care about. */
    static class Answer {
        public String value;

        Answer(String value) {
            this.value = value;
        }
    }

    /** A GeminiClient that replays scripted outcomes and records the prompts it was given. */
    static class StubGemini extends GeminiClient {
        private final Deque<Object> script = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();

        StubGemini answering(Object... outcomes) {
            for (Object outcome : outcomes) {
                script.add(outcome);
            }
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> AiResult<T> generateJson(String prompt, Class<T> type) {
            prompts.add(prompt);
            Object next = script.isEmpty() ? new AiUnavailableException("script exhausted") : script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return new AiResult<>((T) next, "stub-model");
        }
    }

    private static AiStepRunner runnerWith(StubGemini gemini, int maxRepairAttempts) {
        AiStepRunner runner = new AiStepRunner(gemini);
        try {
            Field field = AiStepRunner.class.getDeclaredField("maxRepairAttempts");
            field.setAccessible(true);
            field.setInt(runner, maxRepairAttempts);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the runner's retry budget is no longer a field", e);
        }
        return runner;
    }

    private static final Function<Answer, List<String>> ALWAYS_OK = a -> List.of();
    private static final Function<Answer, List<String>> REQUIRES_GOOD =
            a -> "good".equals(a.value) ? List.of() : List.of("value must be 'good'");

    @Test
    void returnsTheFirstAnswerThatPassesValidation() {
        StubGemini gemini = new StubGemini().answering(new Answer("good"));
        AiStepRunner runner = runnerWith(gemini, 2);

        AiStepRunner.StepResult<Answer> result = runner.run("step", "prompt", Answer.class, ALWAYS_OK);

        assertEquals("good", result.value().value);
        assertEquals("stub-model", result.model());
        assertEquals(0, result.repairAttempts());
        assertEquals(1, gemini.prompts.size());
    }

    /**
     * The correction has to name the specific rule that was broken. A generic "try again" gets the
     * same answer back and spends another call from a per-day quota for nothing.
     */
    @Test
    void feedsTheBrokenRuleBackAndAcceptsTheCorrection() {
        StubGemini gemini = new StubGemini().answering(new Answer("bad"), new Answer("good"));
        AiStepRunner runner = runnerWith(gemini, 2);

        AiStepRunner.StepResult<Answer> result = runner.run("step", "base prompt", Answer.class, REQUIRES_GOOD);

        assertEquals("good", result.value().value);
        assertEquals(1, result.repairAttempts());
        assertEquals(2, gemini.prompts.size());
        assertTrue(gemini.prompts.get(1).contains("value must be 'good'"),
                "the second prompt must quote the rule that was broken");
        assertTrue(gemini.prompts.get(1).startsWith("base prompt"),
                "the correction is appended to the original prompt, not sent on its own");
    }

    /**
     * Fails rather than returning the last bad answer. Returning it would put an invalid plan in
     * front of a student with nothing marking it as invalid.
     */
    @Test
    void failsAfterTheRepairBudgetIsSpent() {
        StubGemini gemini = new StubGemini()
                .answering(new Answer("bad"), new Answer("bad"), new Answer("bad"));
        AiStepRunner runner = runnerWith(gemini, 2);

        AiInvalidResponseException thrown = assertThrows(AiInvalidResponseException.class,
                () -> runner.run("step", "prompt", Answer.class, REQUIRES_GOOD));

        assertTrue(thrown.getMessage().contains("step"));
        assertEquals(3, gemini.prompts.size(), "one initial attempt plus two repairs");
    }

    @Test
    void treatsMalformedJsonAsSomethingWorthCorrecting() {
        StubGemini gemini = new StubGemini()
                .answering(new AiInvalidResponseException("not valid JSON: unexpected token"),
                        new Answer("good"));
        AiStepRunner runner = runnerWith(gemini, 2);

        AiStepRunner.StepResult<Answer> result = runner.run("step", "prompt", Answer.class, ALWAYS_OK);

        assertEquals("good", result.value().value);
        assertTrue(gemini.prompts.get(1).contains("unexpected token"));
    }

    /**
     * An unavailable model is not a correctable answer. Repeating the prompt against the same two
     * models cannot help and would only spend more of a daily quota.
     */
    @Test
    void doesNotRetryWhenNoModelAnswered() {
        StubGemini gemini = new StubGemini()
                .answering(new AiUnavailableException("HTTP 429 quota exhausted"), new Answer("good"));
        AiStepRunner runner = runnerWith(gemini, 2);

        assertThrows(AiUnavailableException.class,
                () -> runner.run("step", "prompt", Answer.class, ALWAYS_OK));
        assertEquals(1, gemini.prompts.size(), "the prompt must not be sent again");
    }

    @Test
    void honoursAZeroRepairBudget() {
        StubGemini gemini = new StubGemini().answering(new Answer("bad"), new Answer("good"));
        AiStepRunner runner = runnerWith(gemini, 0);

        assertThrows(AiInvalidResponseException.class,
                () -> runner.run("step", "prompt", Answer.class, REQUIRES_GOOD));
        assertEquals(1, gemini.prompts.size());
    }
}
