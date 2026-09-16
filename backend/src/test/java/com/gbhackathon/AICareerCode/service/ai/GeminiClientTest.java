package com.gbhackathon.AICareerCode.service.ai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model chain is a product decision, not an implementation detail: the earlier version carried
 * four guessed model names, so when the primary was exhausted the answer quietly came from
 * whichever model still had quota. These pin the rule that exactly two configured models are ever
 * contacted, and that an unconfigured client fails loudly rather than returning nothing.
 */
class GeminiClientTest {

    private static GeminiClient clientWith(String apiKey, String primary, String fallback) {
        GeminiClient client = new GeminiClient();
        set(client, "apiKey", apiKey);
        set(client, "primaryModel", primary);
        set(client, "fallbackModel", fallback);
        return client;
    }

    private static void set(GeminiClient client, String fieldName, String value) {
        try {
            Field field = GeminiClient.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(client, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GeminiClient no longer has a field called " + fieldName, e);
        }
    }

    @Test
    void contactsExactlyTheTwoConfiguredModels() {
        GeminiClient client = clientWith("key", "gemini-3.1-flash-lite", "gemini-3.5-flash");

        assertEquals(List.of("gemini-3.1-flash-lite", "gemini-3.5-flash"), client.modelChain());
    }

    @Test
    void doesNotListTheSameModelTwiceWhenPrimaryAndFallbackMatch() {
        GeminiClient client = clientWith("key", "gemini-3.5-flash", "gemini-3.5-flash");

        assertEquals(List.of("gemini-3.5-flash"), client.modelChain());
    }

    @Test
    void worksWithOnlyAPrimaryConfigured() {
        GeminiClient client = clientWith("key", "gemini-3.5-flash", "");

        assertEquals(List.of("gemini-3.5-flash"), client.modelChain());
    }

    /**
     * Failure is an exception, not a null. Returning null invited every caller to substitute text
     * written in advance and present it as analysis.
     */
    @Test
    void failsLoudlyWhenNoApiKeyIsConfigured() {
        GeminiClient client = clientWith("", "gemini-3.5-flash", "gemini-3.1-flash-lite");

        assertFalse(client.isConfigured());
        AiUnavailableException thrown =
                assertThrows(AiUnavailableException.class, () -> client.generateText("hello"));
        assertTrue(thrown.getMessage().contains("GEMINI_API_KEY"));
    }

    @Test
    void treatsThePlaceholderKeyAsUnconfigured() {
        assertFalse(clientWith("YOUR_GEMINI_API_KEY", "a", "b").isConfigured());
    }

    @Test
    void failsWhenNoModelIsConfiguredAtAll() {
        GeminiClient client = clientWith("key", "", "");

        AiUnavailableException thrown =
                assertThrows(AiUnavailableException.class, () -> client.generateText("hello"));
        assertTrue(thrown.getMessage().contains("GEMINI_PRIMARY_MODEL"));
    }

    /** The diagnostics endpoint must never be able to leak the key it is reporting on. */
    @Test
    void statusReportsConfigurationWithoutExposingTheKey() {
        GeminiClient client = clientWith("super-secret-key", "gemini-3.1-flash-lite", "gemini-3.5-flash");

        Map<String, Object> status = client.status();

        assertEquals(true, status.get("apiKeyConfigured"));
        assertEquals("gemini-3.1-flash-lite", status.get("primaryModel"));
        assertEquals("gemini-3.5-flash", status.get("fallbackModel"));
        assertFalse(status.toString().contains("super-secret-key"));
    }

    @Test
    void probeReportsUnreachableRatherThanThrowingWhenUnconfigured() {
        Map<String, Object> probe = clientWith("", "a", "b").probe();

        assertEquals(false, probe.get("reachable"));
        assertEquals(null, probe.get("reply"));
    }
}
