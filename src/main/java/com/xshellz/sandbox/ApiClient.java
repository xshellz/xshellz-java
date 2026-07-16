package com.xshellz.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Thin bearer-authenticated JSON client for the xShellz control plane with
 * typed error mapping. Internal.
 */
final class ApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_AGENT = "xshellz-java/0.1.0";

    /**
     * 403 message fragments emitted by the control plane's guard chain. All
     * guards abort 403 with an English sentence; quota/entitlement are
     * distinguished by message text.
     */
    private static final String[] QUOTA_FRAGMENTS = {
            "agent shell limit", // "You've reached your plan's agent shell limit (N)."
            "plan does not include agent shells", // entitlement gate
    };

    private final HttpClient http;
    private final String apiUrl;
    private final String apiKey;
    private final Duration timeout;

    ApiClient(String apiKey, String apiUrl, Duration timeout) {
        this.apiKey = Config.resolveApiKey(apiKey);
        this.apiUrl = Config.resolveApiUrl(apiUrl);
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    static ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }

    JsonNode get(String path) {
        return request("GET", path, null);
    }

    JsonNode post(String path, JsonNode body) {
        return request("POST", path, body);
    }

    JsonNode delete(String path) {
        return request("DELETE", path, null);
    }

    /** Issues a request; returns the parsed JSON body or throws a typed error. */
    JsonNode request(String method, String path, JsonNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new XshellzException("Request to the xShellz control plane failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XshellzException("Request to the xShellz control plane was interrupted.", e);
        }

        if (response.statusCode() >= 400) {
            throw mapError(response.statusCode(), response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new XshellzException("The control plane returned invalid JSON: " + e.getMessage(), e);
        }
    }

    private static XshellzException mapError(int status, String rawBody) {
        Object body;
        String message = "";
        String errorCode = "";
        try {
            JsonNode json = MAPPER.readTree(rawBody == null ? "" : rawBody);
            body = json;
            if (json.isObject()) {
                message = json.path("message").asText("");
                errorCode = json.path("error").asText("");
            }
        } catch (JsonProcessingException e) {
            body = rawBody;
        }
        if (message.isEmpty()) {
            message = rawBody == null || rawBody.isBlank() ? "HTTP " + status : rawBody;
        }

        if (status == 401) {
            return new AuthException(
                    "Authentication failed (401): the API key is missing, invalid, expired, "
                            + "or revoked. Create a personal access token with `read` and `write` "
                            + "scopes from your xShellz dashboard (Settings -> API tokens) or via "
                            + "POST /v1/auth/tokens. Server said: " + message);
        }

        if (status == 403) {
            String lowered = message.toLowerCase(Locale.ROOT);
            for (String fragment : QUOTA_FRAGMENTS) {
                if (lowered.contains(fragment)) {
                    return new QuotaException(
                            message + " Tip: on the free tier only one sandbox may exist at a "
                                    + "time - use Sandbox.list() and Sandbox.connect() to attach "
                                    + "to the existing box, or kill() it first.");
                }
            }
            if ("verification_required".equals(errorCode)) {
                return new AuthException("Account verification required (403): " + message);
            }
            return new AuthException("Forbidden (403): " + message);
        }

        if (status == 429) {
            return new ApiException(
                    "Rate limited (429): " + message + " - sandbox creation is throttled to "
                            + "10 requests/minute.",
                    status,
                    body);
        }

        return new ApiException("HTTP " + status + ": " + message, status, body);
    }
}
