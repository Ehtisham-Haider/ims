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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Non-blocking {@link HttpClient} wrapper for free Groq chat-completion models
 * ({@code openai/gpt-oss-20b}, {@code openai/gpt-oss-120b}, {@code qwen/qwen3.8-27b}, {@code allam-2-7b})
 * with user-configured API key persistence, live model discovery, retired-model migration, and detailed diagnostics.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class GroqApiClient {
    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODELS_ENDPOINT = "https://api.groq.com/openai/v1/models";

    /** Default active Groq chat completion model on the free tier. */
    public static final String DEFAULT_MODEL = "openai/gpt-oss-20b";

    /** Free-tier Groq chat models selectable from Settings. */
    public static final List<String> SUPPORTED_MODELS = List.of(
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "qwen/qwen3.8-27b",
            "allam-2-7b"
    );

    private static final Set<String> RETIRED_MODELS = Set.of(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "qwen/qwen3-32b",
            "llama3-8b-8192",
            "llama3-70b-8192",
            "mixtral-8x7b-32768",
            "gemma2-9b-it"
    );

    private static final List<String> NON_CHAT_KEYWORDS = List.of(
            "whisper", "orpheus", "tts", "prompt-guard", "safeguard",
            "llama-guard", "playai", "embed", "rerank", "moderation"
    );

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>.*?</think>");

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
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
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
     * Returns whether a Groq API key is currently configured in Settings or environment.
     *
     * @return {@code true} if an API key is available
     */
    public boolean isApiKeyConfigured() {
        return !resolveApiKey().isBlank();
    }

    /**
     * Resolves the preferred Groq model ID, automatically migrating retired defaults to {@link #DEFAULT_MODEL}.
     *
     * @return active Groq model identifier string
     */
    public String resolveModel() {
        String model = settingsRepository != null ? settingsRepository.get("groq.model", DEFAULT_MODEL) : DEFAULT_MODEL;
        if (model == null || model.isBlank()) {
            return DEFAULT_MODEL;
        }
        String trimmed = model.trim();
        if (isRetiredModel(trimmed)) {
            if (settingsRepository != null) {
                settingsRepository.set("groq.model", DEFAULT_MODEL);
            }
            return DEFAULT_MODEL;
        }
        return trimmed;
    }

    /**
     * Queries {@code GET /openai/v1/models} with the supplied API key and returns supported chat-completion
     * models available to this Groq account, excluding audio, TTS, moderation, and guard models.
     *
     * @param apiKey Groq API key
     * @return ordered list of available chat model IDs, or an empty list if unreachable/unauthorized
     */
    public List<String> fetchAvailableChatModels(String apiKey) {
        String token = apiKey != null ? apiKey.trim() : "";
        if (token.isBlank()) {
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_MODELS_ENDPOINT))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonArray()) {
                return List.of();
            }
            List<String> rawIds = new ArrayList<>();
            for (var el : root.getAsJsonArray("data")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("active") && !obj.get("active").isJsonNull() && !obj.get("active").getAsBoolean()) {
                    continue;
                }
                if (obj.has("id") && !obj.get("id").isJsonNull()) {
                    rawIds.add(obj.get("id").getAsString());
                }
            }
            return filterChatModels(rawIds);
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * Filters raw Groq {@code /models} identifiers to general-purpose chat models and orders preferred
     * defaults first followed by remaining models alphabetically.
     *
     * @param rawModelIds raw model identifiers returned by Groq
     * @return filtered and deduplicated list of chat model identifiers
     */
    public static List<String> filterChatModels(List<String> rawModelIds) {
        if (rawModelIds == null || rawModelIds.isEmpty()) {
            return List.of();
        }
        Set<String> valid = new LinkedHashSet<>();
        for (String id : rawModelIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            boolean nonChat = NON_CHAT_KEYWORDS.stream().anyMatch(lower::contains);
            if (!nonChat) {
                valid.add(trimmed);
            }
        }
        if (valid.isEmpty()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String preferred : SUPPORTED_MODELS) {
            for (String candidate : valid) {
                if (candidate.equalsIgnoreCase(preferred)) {
                    ordered.add(candidate);
                }
            }
        }
        List<String> remaining = new ArrayList<>();
        for (String candidate : valid) {
            if (!ordered.contains(candidate)) {
                remaining.add(candidate);
            }
        }
        remaining.sort(Comparator.naturalOrder());
        ordered.addAll(remaining);
        return List.copyOf(ordered);
    }

    /**
     * Strips inline {@code <think>...</think>} reasoning blocks emitted by reasoning models and trims whitespace.
     *
     * @param rawContent raw assistant message content
     * @return clean plain-text reply
     */
    public static String sanitizeModelContent(String rawContent) {
        if (rawContent == null) {
            return "";
        }
        return THINK_BLOCK_PATTERN.matcher(rawContent).replaceAll("").trim();
    }

    /**
     * Saves the user's Groq API key and selected model into persistent SQLite settings.
     *
     * @param apiKey user-supplied Groq API key
     * @param model selected model identifier, or {@code null}/blank to keep default
     */
    public void saveConfiguration(String apiKey, String model) {
        if (settingsRepository == null) {
            return;
        }
        settingsRepository.set("groq.api_key", apiKey != null ? apiKey.trim() : "");
        String cleanModel = model != null && !model.isBlank() ? model.trim() : DEFAULT_MODEL;
        settingsRepository.set("groq.model", cleanModel);
    }

    /**
     * Clears any saved Groq API key in SQLite settings.
     */
    public void clearApiKey() {
        if (settingsRepository != null) {
            settingsRepository.set("groq.api_key", "");
        }
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
     * Tests connectivity against Groq using an explicit API key and currently configured model.
     *
     * @param apiKey candidate Groq API key
     * @return {@link GroqResponse} verifying key validity and reachability
     */
    public GroqResponse testConnection(String apiKey) {
        return testConnection(apiKey, resolveModel());
    }

    /**
     * Tests connectivity against Groq using an explicit API key and target model.
     *
     * @param apiKey candidate Groq API key
     * @param model target Groq model ID
     * @return {@link GroqResponse} verifying key validity and reachability
     */
    public GroqResponse testConnection(String apiKey, String model) {
        String resolvedModel = (model == null || model.isBlank() || isRetiredModel(model))
                ? resolveModel()
                : model.trim();
        if (apiKey == null || apiKey.isBlank()) {
            return new GroqResponse(false, resolvedModel, "",
                    "Enter your free Groq API key (gsk_...) to enable cloud AI features.");
        }
        return completeWithKey(apiKey.trim(), resolvedModel,
                "Reply briefly in plain text.", "Respond with OK if StockPro connection is active.");
    }

    /**
     * Executes a Groq chat-completion call and automatically retries once against a live account chat model
     * if the configured model returns an unavailable/decommissioned error.
     *
     * @param apiKey Groq bearer token
     * @param model target model id
     * @param systemPrompt system context
     * @param userPrompt user question
     * @return {@link GroqResponse}
     */
    private GroqResponse completeWithKey(String apiKey, String model, String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            return new GroqResponse(false, "offline-engine", "", "No Groq API key set — configure it in Settings.");
        }
        String cleanKey = apiKey.trim();
        String targetModel = (model == null || model.isBlank() || isRetiredModel(model))
                ? DEFAULT_MODEL
                : model.trim();

        AttemptResult attempt = sendChatCompletion(cleanKey, targetModel, systemPrompt, userPrompt);
        if (attempt.response().success()) {
            return attempt.response();
        }
        if (isModelUnavailableError(attempt.statusCode(), attempt.rawBody())) {
            List<String> live = fetchAvailableChatModels(cleanKey);
            List<String> candidates = live.isEmpty() ? SUPPORTED_MODELS : live;
            for (String fallbackModel : candidates) {
                if (!fallbackModel.equalsIgnoreCase(targetModel)) {
                    AttemptResult retry = sendChatCompletion(cleanKey, fallbackModel, systemPrompt, userPrompt);
                    if (retry.response().success() && settingsRepository != null) {
                        settingsRepository.set("groq.model", fallbackModel);
                    }
                    return retry.response();
                }
            }
        }
        return attempt.response();
    }

    /**
     * Sends a single HTTP POST request to Groq chat completions and decodes content safely.
     *
     * @param apiKey Groq bearer token
     * @param model target model identifier
     * @param systemPrompt system prompt
     * @param userPrompt user prompt
     * @return raw HTTP status, response body, and mapped {@link GroqResponse}
     */
    private AttemptResult sendChatCompletion(String apiKey, String model, String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("temperature", 0.2);
            payload.put("max_completion_tokens", 1536);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));
            String jsonBody = gson.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_ENDPOINT))
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (root.has("choices") && root.get("choices").isJsonArray()) {
                    JsonArray choices = root.getAsJsonArray("choices");
                    if (!choices.isEmpty() && choices.get(0).isJsonObject()) {
                        JsonObject first = choices.get(0).getAsJsonObject();
                        if (first.has("message") && first.get("message").isJsonObject()) {
                            JsonObject message = first.getAsJsonObject("message");
                            String raw = (message.has("content") && !message.get("content").isJsonNull())
                                    ? message.get("content").getAsString()
                                    : "";
                            String text = sanitizeModelContent(raw);
                            if (text.isBlank() && message.has("reasoning") && !message.get("reasoning").isJsonNull()) {
                                text = sanitizeModelContent(message.get("reasoning").getAsString());
                            }
                            if (!text.isBlank()) {
                                return new AttemptResult(response.statusCode(), response.body(),
                                        new GroqResponse(true, model, text, null));
                            }
                            return new AttemptResult(response.statusCode(), response.body(),
                                    new GroqResponse(false, model, "",
                                            "Groq returned an empty response for model " + model + "."));
                        }
                    }
                }
            }
            return new AttemptResult(response.statusCode(), response.body(),
                    new GroqResponse(false, model, "", formatHttpError(response.statusCode(), response.body())));
        } catch (Exception ex) {
            String msg = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage() : ex.getClass().getSimpleName();
            return new AttemptResult(0, "",
                    new GroqResponse(false, model, "", "Cannot reach api.groq.com: " + msg));
        }
    }

    /**
     * Checks whether a model ID matches a previously retired Groq model identifier.
     *
     * @param model candidate model identifier
     * @return {@code true} if known retired
     */
    private static boolean isRetiredModel(String model) {
        return model != null && RETIRED_MODELS.contains(model.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Detects HTTP responses indicating the requested model is retired, inaccessible, or unknown.
     *
     * @param statusCode HTTP status code
     * @param rawBody raw response body
     * @return {@code true} when automatic fallback to another chat model should be attempted
     */
    private boolean isModelUnavailableError(int statusCode, String rawBody) {
        if (statusCode == 404) {
            return true;
        }
        if (statusCode == 400 && rawBody != null) {
            String lower = rawBody.toLowerCase(Locale.ROOT);
            return lower.contains("model") && (lower.contains("decommissioned")
                    || lower.contains("does not exist")
                    || lower.contains("not found")
                    || lower.contains("not supported")
                    || lower.contains("invalid"));
        }
        return false;
    }

    /**
     * Extracts structured error messages from Groq error JSON payloads for actionable diagnostics.
     *
     * @param status HTTP status code
     * @param body raw HTTP response body
     * @return human-readable error message
     */
    private String formatHttpError(int status, String body) {
        String apiMsg = "";
        if (body != null && !body.isBlank()) {
            try {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (root.has("error") && root.get("error").isJsonObject()) {
                    JsonObject err = root.getAsJsonObject("error");
                    if (err.has("message")) {
                        apiMsg = err.get("message").getAsString();
                    }
                }
            } catch (Exception ignored) {
                // Fall back to status-based diagnostic below.
            }
        }
        String suffix = apiMsg.isBlank() ? "" : ": " + apiMsg;
        return switch (status) {
            case 401 -> "Invalid Groq API key (HTTP 401)" + suffix;
            case 403 -> "Groq access forbidden (HTTP 403)" + suffix;
            case 429 -> "Groq rate limit reached (HTTP 429)" + suffix;
            case 400, 404 -> "Groq request/model error (HTTP " + status + ")" + suffix;
            default -> "Groq HTTP " + status + suffix;
        };
    }

    /**
     * Internal single HTTP attempt holder used for model fallback logic.
     *
     * @param statusCode HTTP status code
     * @param rawBody raw response payload
     * @param response decoded client outcome
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    private record AttemptResult(int statusCode, String rawBody, GroqResponse response) {
    }
}
