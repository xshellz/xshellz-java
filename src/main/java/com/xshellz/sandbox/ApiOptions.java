package com.xshellz.sandbox;

import java.time.Duration;

/**
 * Control-plane client options for {@link Sandbox#connect(String, String, ApiOptions)}
 * and {@link Sandbox#list(ApiOptions)}.
 *
 * <p>Config precedence: explicit option &gt; {@code XSHELLZ_API_KEY} /
 * {@code XSHELLZ_API_URL} environment variables &gt; default
 * ({@code https://api.xshellz.com/v1}).
 */
public final class ApiOptions {

    private final String apiKey;
    private final String apiUrl;
    private final Duration timeout;

    private ApiOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiUrl = builder.apiUrl;
        this.timeout = builder.timeout;
    }

    /** Options resolving everything from the environment/defaults. */
    public static ApiOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The explicit API key, or {@code null} to resolve from the environment. */
    public String apiKey() {
        return apiKey;
    }

    /** The explicit API base URL, or {@code null} to resolve from the environment. */
    public String apiUrl() {
        return apiUrl;
    }

    /** The HTTP request timeout (default 120 seconds). */
    public Duration timeout() {
        return timeout;
    }

    /** Builder for {@link ApiOptions}. */
    public static final class Builder {
        private String apiKey;
        private String apiUrl;
        private Duration timeout = Duration.ofSeconds(120);

        private Builder() {
        }

        /** A personal access token with {@code read} and {@code write} scopes. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Base URL of the control plane, e.g. a staging override. */
        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        /** HTTP request timeout. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public ApiOptions build() {
            return new ApiOptions(this);
        }
    }
}
