package com.gbhackathon.AICareerCode.service;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single entry point for every Google Gemini call in the application.
 *
 * <p>Previously each service called Gemini inline with the API key in the query string, no timeout,
 * and a catch-all that logged only {@code e.getMessage()}. When the configured model ran out of
 * free-tier quota (gemini-3.5-flash allows 20 requests/day) every AI feature silently degraded to
 * hardcoded placeholder text with no visible signal. This client instead:
 *
 * <ul>
 *   <li>sends the key as the {@code x-goog-api-key} header so it never lands in URLs or access logs</li>
 *   <li>walks a fallback chain of models when one is exhausted (429) or unavailable (404)</li>
 *   <li>logs the actual HTTP response body instead of a generic message</li>
 *   <li>remembers the last working model so later calls skip the exhausted ones</li>
 * </ul>
 */
@Service
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    @Value("${ai.gemini.api-key:}")
    private String apiKey;

    @Value("${ai.gemini.model:}")
    private String primaryModel;

    @Value("${ai.gemini.fallback-models:}")
    private String fallbackModels;

    @Value("${ai.gemini.timeout-seconds:45}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Last model that actually produced text; promoted to the front of the chain on later calls. */
    private final AtomicReference<String> lastWorkingModel = new AtomicReference<>();
    /** Last failure, surfaced through {@link #status()} so the UI can explain why AI output is missing. */
    private final AtomicReference<String> lastError = new AtomicReference<>();

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

    /**
     * Model chain, most preferred first. The configured model is tried first so operators keep
     * control, then the last known working model, then the broadly available flash models.
     */
    public List<String> modelChain() {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        if (primaryModel != null && !primaryModel.isBlank()) {
            chain.add(primaryModel.trim());
        }
        String working = lastWorkingModel.get();
        if (working != null) {
            chain.add(working);
        }
        if (fallbackModels != null && !fallbackModels.isBlank()) {
            Arrays.stream(fallbackModels.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(chain::add);
        }
        return new ArrayList<>(chain);
    }

    /** Free-form generation. Returns {@code null} when every model in the chain fails. */
    public String generateText(String prompt) {
        return generate(prompt, false, 4096);
    }

    /** Generation constrained to a JSON response. Returns the raw JSON string, or {@code null}. */
    public String generateJson(String prompt) {
        return generate(prompt, true, 8192);
    }

    /** Generation constrained to JSON and deserialized into {@code type}. Returns {@code null} on failure. */
    public <T> T generateJson(String prompt, Class<T> type) {
        String json = generateJson(prompt);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(stripCodeFence(json), type);
        } catch (Exception e) {
            lastError.set("Response was not valid JSON: " + e.getMessage());
            log.warn("Gemini returned JSON that could not be mapped to {}: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private String generate(String prompt, boolean jsonMode, int maxOutputTokens) {
        if (!isConfigured()) {
            lastError.set("GEMINI_API_KEY is not configured");
            return null;
        }

        List<String> chain = modelChain();
        if (chain.isEmpty()) {
            lastError.set("No Gemini model configured");
            return null;
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        generationConfig.put("temperature", jsonMode ? 0.3 : 0.8);
        if (jsonMode) {
            generationConfig.put("response_mime_type", "application/json");
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", generationConfig
        );

        for (String model : chain) {
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
                    lastError.set(null);
                    log.debug("Gemini call served by model {}", model);
                    return text;
                }
                // A 200 with no text usually means the response hit a finish reason such as
                // MAX_TOKENS (thinking models can spend the whole budget) or SAFETY.
                String reason = extractFinishReason(raw);
                lastError.set("Model " + model + " returned no text (finishReason=" + reason + ")");
                log.warn("Gemini model {} returned an empty candidate (finishReason={}), trying next model", model, reason);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                String summary = describeApiError(model, status, e.getResponseBodyAsString());
                lastError.set(summary);
                log.warn("Gemini model {} failed: {}", model, summary);
                // 429 (quota) and 404 (model not available to this key) are worth retrying on another
                // model. Anything else - bad key, malformed request - fails identically, so stop.
                if (status != 429 && status != 404 && status < 500) {
                    break;
                }
            } catch (Exception e) {
                lastError.set("Model " + model + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                log.warn("Gemini model {} call failed: {}", model, e.toString());
            }
        }

        log.warn("All Gemini models exhausted; using offline fallback. Last error: {}", lastError.get());
        return null;
    }

    private String describeApiError(String model, int status, String bodyText) {
        try {
            JsonNode error = objectMapper.readTree(bodyText).path("error");
            String message = error.path("message").asText("");
            if (status == 429) {
                // Surface the per-model daily cap: the single most common reason every AI feature
                // quietly returns placeholder content.
                String quota = "";
                for (JsonNode detail : error.path("details")) {
                    for (JsonNode violation : detail.path("violations")) {
                        String value = violation.path("quotaValue").asText("");
                        if (!value.isEmpty()) {
                            quota = " (free-tier limit " + value + " requests/day for " + model + ")";
                        }
                    }
                }
                return "HTTP 429 quota exhausted" + quota;
            }
            return "HTTP " + status + (message.isEmpty() ? "" : ": " + message);
        } catch (Exception ignored) {
            return "HTTP " + status;
        }
    }

    private String extractText(String raw) {
        try {
            JsonNode candidates = objectMapper.readTree(raw).path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : candidates.get(0).path("content").path("parts")) {
                    // Thinking models emit reasoning parts flagged with "thought"; keep answer text only.
                    if (!part.path("thought").asBoolean(false)) {
                        sb.append(part.path("text").asText(""));
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Could not parse Gemini response envelope: {}", e.getMessage());
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
        status.put("modelChain", modelChain());
        status.put("lastWorkingModel", lastWorkingModel.get());
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
        String reply = generateText("Reply with exactly the two characters: OK");
        result.put("reachable", reply != null && !reply.isBlank());
        result.put("reply", reply);
        result.put("lastWorkingModel", lastWorkingModel.get());
        result.put("lastError", lastError.get());
        return result;
    }
}
