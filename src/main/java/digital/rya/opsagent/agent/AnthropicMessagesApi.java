package digital.rya.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal Claude Messages API client on top of java.net.http (no SDK, so every byte sent is
 * visible here). Retries rate-limit / overload / 5xx responses with exponential backoff.
 */
public class AnthropicMessagesApi implements MessagesApi {

    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    public AnthropicMessagesApi(String baseUrl, String apiKey, Duration timeout) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/messages");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public JsonNode createMessage(ObjectNode request) {
        // Checked here, not at startup, so the read-only endpoints work without a key.
        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeApiException("ANTHROPIC_API_KEY is not set", null);
        }
        String body;
        try {
            body = json.writeValueAsString(request);
        } catch (IOException e) {
            throw new ClaudeApiException("Could not serialize request", e);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", API_VERSION)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) {
                    return json.readTree(resp.body());
                }
                boolean retryable = code == 429 || code >= 500;
                last = new ClaudeApiException("Claude API returned HTTP " + code + ": " + abbreviate(resp.body()), null);
                if (!retryable) {
                    throw last;
                }
            } catch (IOException e) {
                last = new ClaudeApiException("Claude API call failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ClaudeApiException("Interrupted while calling Claude API", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleep(1000L * (1L << (attempt - 1))); // 1s, then 2s
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() > 500 ? s.substring(0, 500) + "..." : s);
    }
}
