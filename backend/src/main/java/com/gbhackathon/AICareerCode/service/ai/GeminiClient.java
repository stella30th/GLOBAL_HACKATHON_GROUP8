package com.gbhackathon.AICareerCode.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single entry point for every Google Gemini call in the application.
 *
 * <p>Exactly two models are ever contacted: the configured primary and the configured fallback.
 * The earlier version carried a chain of four guessed model names, so when the primary was
 * exhausted the answer quietly came from whichever model still had quota - the "model" label next
 * to the analysis was the only way to find out, and nobody looked. Two named models, both set
 * through the environment, keep that decision visible.
 *
 * <p>Failure is an exception, not a {@code null}. Returning null invited every caller to
 * substitute text written in advance and present it as analysis, which is the specific thing this
 * product must not do.
 *
 * <p>Fallback is by error class, not blanket retry. Quota (429), a model this key cannot see (404)
 * and a server-side fault (5xx) are worth trying on the other model. A rejected key (401/403) or a
 * malformed request (400) fails identically on both, so the chain stops immediately rather than
 * burning a second round trip and a second misleading log line.
 */
@Service
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    @Value("${ai.gemini.api-key:}")
    private String apiKey;

    @Value("${ai.gemini.primary-model:}")
    private String primaryModel;

    @Value("${ai.gemini.fallback-model:}")
    private String fallbackModel;

    @Value("${ai.gemini.timeout-seconds:60}")
    private int timeoutSeconds;

    /** Attempts per model for a transient transport failure. Deliberately small. */
    @Value("${ai.gemini.max-attempts-per-model:2}")
    private int maxAttemptsPerModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Last failure, surfaced through {@link #status()} so the UI can explain the outage. */
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastWorkingModel = new AtomicReference<>();
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();

    private RestClient restClient;

    private RestClient client() {
        if (restClient == null) {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
            restClient = RestClient.builder().requestFactory(factory).build();
        }
        return restClient;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"YOUR_GEMINI_API_KEY".equals(apiKey);
    }

    /** The primary model followed by the fallback. Never more, never anything unconfigured. */
    public List<String> modelChain() {
        List<String> chain = new ArrayList<>();
        if (primaryModel != null && !primaryModel.isBlank()) {
            chain.add(primaryModel.trim());
        }
        if (fallbackModel != null && !fallbackModel.isBlank() && !chain.contains(fallbackModel.trim())) {
            chain.add(fallbackModel.trim());
        }
        return chain;
    }

    /**
     * Free-form generation for the chat panel.
     *
     * @throws AiUnavailableException when neither model answers
     */
    public String generateText(String prompt) {
        return generate(prompt, false, 4096).text();
    }

    /**
     * JSON-constrained generation, deserialized into {@code type}.
     *
     * @throws AiUnavailableException     when neither model answers
     * @throws AiInvalidResponseException when a model answered with something that is not the
     *                                    requested shape - the caller may retry with feedback
     */
    public <T> AiResult<T> generateJson(String prompt, Class<T> type) {
        Generation generation = generate(prompt, true, 16384);
        try {
            T value = objectMapper.readValue(stripCodeFence(generation.text()), type);
            if (value == null) {
                throw new AiInvalidResponseException("The model returned an empty JSON document.");
            }
            return new AiResult<>(value, generation.model());
        } catch (AiInvalidResponseException e) {
            throw e;
        } catch (Exception e) {
            // The body is derived from the student's CV, so it is never logged. Jackson's message
            // names the offending field and carries no CV content.
            log.warn("Gemini response could not be mapped to {}: {}", type.getSimpleName(), e.getMessage());
            throw new AiInvalidResponseException(
                    "The model's answer was not valid " + type.getSimpleName() + " JSON: " + e.getMessage());
        }
    }

    /** What came back, and which of the two models produced it. */
    public record AiResult<T>(T value, String model) {}

    private record Generation(String text, String model) {}

    private Generation generate(String prompt, boolean jsonMode, int maxOutputTokens) {
        if (!isConfigured()) {
            String reason = "GEMINI_API_KEY is not configured.";
            lastError.set(reason);
            throw new AiUnavailableException(reason);
        }

        List<String> chain = modelChain();
        if (chain.isEmpty()) {
            String reason = "No Gemini model is configured (set GEMINI_PRIMARY_MODEL).";
            lastError.set(reason);
            throw new AiUnavailableException(reason);
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        generationConfig.put("temperature", jsonMode ? 0.2 : 0.7);
        if (jsonMode) {
            generationConfig.put("response_mime_type", "application/json");
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", generationConfig);

        String failure = null;
        for (String model : chain) {
            for (int attempt = 1; attempt <= Math.max(1, maxAttemptsPerModel); attempt++) {
                try {
                    String raw = client().post()
                            .uri(String.format(ENDPOINT, model))
                            .header("x-goog-api-key", apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(String.class);

                    String text = extractText(raw);
                    if (text != null && !text.isBlank()) {
                        lastWorkingModel.set(model);
                        lastSuccessAt.set(Instant.now());
                        lastError.set(null);
                        return new Generation(text, model);
                    }
                    // A 200 with no text means a finish reason such as MAX_TOKENS (thinking models
                    // can spend the whole budget) or SAFETY. Another attempt on the same model will
                    // not help, so move to the fallback.
                    failure = "Model " + model + " returned no text (finishReason="
                            + extractFinishReason(raw) + ")";
                    log.warn("{}", failure);
                    break;
                } catch (RestClientResponseException e) {
                    int status = e.getStatusCode().value();
                    failure = describeApiError(model, status, e.getResponseBodyAsString());
                    log.warn("Gemini model {} failed: {}", model, failure);
                    if (!worthTryingOtherModel(status)) {
                        // A configuration or authentication fault repeats identically on the other
                        // model. Stop the chain rather than flapping between the two.
                        lastError.set(failure);
                        throw new AiUnavailableException(failure);
                    }
                    break;
                } catch (Exception e) {
                    failure = "Model " + model + ": " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " - " + e.getMessage());
                    log.warn("Gemini model {} call failed (attempt {}): {}", model, attempt, failure);
                    // A timeout or a dropped connection is transient; one more attempt is worth it.
                }
            }
        }

        lastError.set(failure);
        throw new AiUnavailableException(failure);
    }

    /**
     * Whether the other model has any chance of succeeding where this one failed.
     * 429 is quota, 404 is a model this key cannot see, 5xx is Google's side.
     */
    private boolean worthTryingOtherModel(int status) {
        return status == 429 || status == 404 || status >= 500;
    }

    private String describeApiError(String model, int status, String bodyText) {
        try {
            JsonNode error = objectMapper.readTree(bodyText).path("error");
            String message = error.path("message").asText("");
            if (status == 429) {
                String quota = "";
                for (JsonNode detail : error.path("details")) {
                    for (JsonNode violation : detail.path("violations")) {
                        String value = violation.path("quotaValue").asText("");
                        if (!value.isEmpty()) {
                            quota = " (free-tier limit " + value + " requests/day for " + model + ")";
                        }
                    }
                }
                return "HTTP 429 quota exhausted on " + model + quota;
            }
            if (status == 401 || status == 403) {
                return "HTTP " + status + " on " + model + ": the API key was rejected"
                        + (message.isEmpty() ? "" : " - " + message);
            }
            return "HTTP " + status + " on " + model + (message.isEmpty() ? "" : ": " + message);
        } catch (Exception ignored) {
            return "HTTP " + status + " on " + model;
        }
    }

    private String extractText(String raw) {
        try {
            JsonNode candidates = objectMapper.readTree(raw).path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : candidates.get(0).path("content").path("parts")) {
                    // Thinking models emit reasoning parts flagged with "thought"; keep answers only.
                    if (!part.path("thought").asBoolean(false)) {
                        sb.append(part.path("text").asText(""));
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Could not parse the Gemini response envelope: {}", e.getMessage());
        }
        return null;
    }

    private String extractFinishReason(String raw) {
        try {
            JsonNode candidates = objectMapper.readTree(raw).path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                return candidates.get(0).path("finishReason").asText("UNKNOWN");
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "UNKNOWN";
    }

    /** Models occasionally wrap JSON in a code fence despite response_mime_type. */
    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    /** Diagnostic snapshot for {@code GET /api/coach/ai-status}. Never exposes the key itself. */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("apiKeyConfigured", isConfigured());
        status.put("primaryModel", primaryModel);
        status.put("fallbackModel", fallbackModel);
        status.put("modelChain", modelChain());
        status.put("lastWorkingModel", lastWorkingModel.get());
        status.put("lastSuccessAt", lastSuccessAt.get() != null ? lastSuccessAt.get().toString() : null);
        status.put("lastError", lastError.get());
        return status;
    }

    /** Live probe used by the diagnostics endpoint: performs one real round trip. */
    public Map<String, Object> probe() {
        Map<String, Object> result = new LinkedHashMap<>(status());
        if (!isConfigured()) {
            result.put("reachable", false);
            result.put("reply", null);
            return result;
        }
        try {
            String reply = generateText("Reply with exactly the two characters: OK");
            result.put("reachable", true);
            result.put("reply", reply);
        } catch (AiUnavailableException e) {
            result.put("reachable", false);
            result.put("reply", null);
        }
        result.put("lastWorkingModel", lastWorkingModel.get());
        result.put("lastError", lastError.get());
        return result;
    }
}
