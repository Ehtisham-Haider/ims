package com.stockpro.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Groq Speech-to-Text client targeting {@code whisper-large-v3-turbo} over zero-cost
 * multipart HTTP upload, with deterministic offline transcript fallback when no key or network exists.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class GroqWhisperClient {

    /** Active Groq free-tier Whisper speech transcription model. */
    public static final String WHISPER_MODEL = "whisper-large-v3-turbo";

    private static final String TRANSCRIPTIONS_ENDPOINT =
            "https://api.groq.com/openai/v1/audio/transcriptions";

    private final GroqApiClient groqApiClient;
    private final MultipartTransport transport;

    /**
     * Functional HTTP multipart transport abstraction enabling 100% offline deterministic unit tests.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    @FunctionalInterface
    public interface MultipartTransport {
        /**
         * Executes an HTTP multipart POST and returns the raw JSON response string (or {@code null} on failure).
         *
         * @param endpoint target transcription URL
         * @param apiKey Groq API bearer token
         * @param boundary multipart boundary token
         * @param bodyBytes complete multipart body payload
         * @return raw response JSON or {@code null}
         */
        String post(String endpoint, String apiKey, String boundary, byte[] bodyBytes);
    }

    /**
     * Speech-to-text transcription result carrying cloud attribution and fallback diagnostics.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param usedCloud {@code true} when Groq Whisper returned live transcribed speech
     * @param model transcription model id
     * @param text recognized spoken command text
     * @param diagnostic optional fallback diagnostic note
     */
    public record WhisperTranscript(
            boolean usedCloud,
            String model,
            String text,
            String diagnostic
    ) {
    }

    /**
     * Constructs a Groq Whisper speech client using the standard JDK {@link HttpClient}.
     *
     * @param groqApiClient configured Groq client providing API key resolution
     */
    public GroqWhisperClient(GroqApiClient groqApiClient) {
        this(groqApiClient, GroqWhisperClient::defaultPost);
    }

    /**
     * Constructs a Groq Whisper client with an explicit multipart HTTP transport for offline tests.
     *
     * @param groqApiClient configured Groq client
     * @param transport custom multipart HTTP transport
     */
    public GroqWhisperClient(GroqApiClient groqApiClient, MultipartTransport transport) {
        this.groqApiClient = groqApiClient;
        this.transport = transport != null ? transport : GroqWhisperClient::defaultPost;
    }

    /**
     * Transcribes WAV audio bytes via Groq {@code whisper-large-v3-turbo}, falling back cleanly
     * to {@code offlineFallbackText} if no API key is configured or network is unreachable.
     *
     * @param wavAudio valid WAV audio byte array
     * @param offlineFallbackText optional manual or fallback spoken command text
     * @return {@link WhisperTranscript}
     */
    public WhisperTranscript transcribe(byte[] wavAudio, String offlineFallbackText) {
        String apiKey = groqApiClient != null ? groqApiClient.resolveApiKey() : "";
        if (apiKey == null || apiKey.isBlank()) {
            return new WhisperTranscript(
                    false,
                    WHISPER_MODEL,
                    offlineFallbackText != null ? offlineFallbackText.trim() : "",
                    "Groq API key not configured; using local typed/fallback voice command."
            );
        }
        if (wavAudio == null || wavAudio.length < 44) {
            return new WhisperTranscript(
                    false,
                    WHISPER_MODEL,
                    offlineFallbackText != null ? offlineFallbackText.trim() : "",
                    "Audio buffer empty; skipped cloud Whisper upload."
            );
        }

        try {
            String boundary = "----StockProWhisperBoundary7A9C";
            byte[] payload = buildMultipartBody(boundary, wavAudio);
            String json = transport.post(TRANSCRIPTIONS_ENDPOINT, apiKey.trim(), boundary, payload);
            if (json != null && !json.isBlank()) {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("text") && !obj.get("text").isJsonNull()) {
                    String text = obj.get("text").getAsString().trim();
                    if (!text.isBlank()) {
                        return new WhisperTranscript(true, WHISPER_MODEL, text, "Transcribed live via Groq Whisper.");
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back cleanly below
        }

        return new WhisperTranscript(
                false,
                WHISPER_MODEL,
                offlineFallbackText != null ? offlineFallbackText.trim() : "",
                "Groq Whisper offline or unreachable; preserved local fallback command."
        );
    }

    private byte[] buildMultipartBody(String boundary, byte[] wavAudio) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"model\"\r\n\r\n");
        writeAscii(out, WHISPER_MODEL + "\r\n");

        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n");
        writeAscii(out, "json\r\n");

        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n");
        writeAscii(out, "Content-Type: audio/wav\r\n\r\n");
        out.write(wavAudio);
        writeAscii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private void writeAscii(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String defaultPost(String endpoint, String apiKey, String boundary, byte[] bodyBytes) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300 ? resp.body() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
