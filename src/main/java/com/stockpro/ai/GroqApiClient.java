package com.stockpro.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stockpro.repository.SettingsRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Non-blocking {@link HttpClient} wrapper for free Groq chat-completion models
 * ({@code llama-3.3-70b-versatile} / {@code llama-4-scout}) with automatic offline fallback.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class GroqApiClient {
    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String DEFAULT_MODEL = "llama-3.3-70b-versatile";

    private final SettingsRepository settingsRepository;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /**
     * Structured outcome returned from a Groq API call or offline fallback check.
     *
     * @param success {@code true} when cloud response succeeded
     * @param model model identifier used
     * @param content response text content
     * @param errorNote optional diagnostic message when falling back offline
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record GroqResponse(boolean success, String model, String content, String errorNote) {
    }

    /**
     * Creates a Groq client reading API keys from {@link SettingsRepository} or {@code GROQ_API_KEY}.
     *
     * @param settingsRepository persisted settings repository
     */
    public GroqApiClient(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    /**
     * Resolves the active Groq API key from saved SQLite settings or environment variable.
     *
     * @return API key string, or empty string if unconfigured
     */
    public String resolveApiKey() {
        String configured = settingsRepository != null ? settingsRepository.get("groq.api_key", "") : "";
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String envKey = System.getenv("GROQ_API_KEY");
        return envKey != null ? envKey.trim() : "";
    }

    /**
     * Resolves the preferred Groq model ID.
     *
     * @return model identifier string
     */
    public String resolveModel() {
        String model = settingsRepository != null ? settingsRepository.get("groq.model", DEFAULT_MODEL) : DEFAULT_MODEL;
        return model != null && !model.isBlank() ? model.trim() : DEFAULT_MODEL;
    }

    /**
     * Calls the Groq chat completions API with a system prompt and user message, falling back cleanly if
     * no key is configured or the network is unavailable.
     *
     * @param systemPrompt grounded system instruction
     * @param userPrompt user prompt
     * @return {@link GroqResponse} containing cloud text or fallback indicator
     */
    public GroqResponse complete(String systemPrompt, String userPrompt) {
        return completeWithKey(resolveApiKey(), resolveModel(), systemPrompt, userPrompt);
    }

    /**
     * Tests connectivity against Groq using an explicit API key.
     *
     * @param apiKey candidate Groq API key
     * @return {@link GroqResponse} verifying key validity and reachability
     */
    public GroqResponse testConnection(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return new GroqResponse(false, resolveModel(), "", "Enter a free Groq API key first (Offline fallback remains active).");
        }
        return completeWithKey(apiKey.trim(), resolveModel(),
                "Reply briefly.", "Respond with OK if StockPro connection is active.");
    }

    /**
     * Executes the HTTP POST request against Groq OpenAI-compatible completions API.
     *
     * @param apiKey Groq bearer token
     * @param model target model id
     * @param systemPrompt system context
     * @param userPrompt user question
     * @return {@link GroqResponse}
     */
    private GroqResponse completeWithKey(String apiKey, String model, String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            return new GroqResponse(false, "offline-engine", "", "No Groq API key set — using local offline engine.");
        }
        try {
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "max_tokens", 550,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );
            String jsonBody = gson.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    String content = choices.get(0).getAsJsonObject()
                            .getAsJsonObject("message")
                            .get("content").getAsString();
                    return new GroqResponse(true, model, content.trim(), null);
                }
            }
            return new GroqResponse(false, "offline-engine", "", "Groq HTTP " + response.statusCode() + " — switched to offline fallback.");
        } catch (Exception ex) {
            return new GroqResponse(false, "offline-engine", "", "Offline mode (" + ex.getClass().getSimpleName() + ")");
        }
    }
}
