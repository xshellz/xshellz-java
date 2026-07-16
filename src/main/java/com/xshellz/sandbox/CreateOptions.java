package com.xshellz.sandbox;

import java.time.Duration;

/**
 * Options for {@link Sandbox#create(CreateOptions)}.
 *
 * <p>Config precedence: explicit option &gt; {@code XSHELLZ_API_KEY} /
 * {@code XSHELLZ_API_URL} environment variables &gt; default
 * ({@code https://api.xshellz.com/v1}).
 */
public final class CreateOptions {

    private final String name;
    private final String apiKey;
    private final String apiUrl;
    private final Duration timeout;

    private CreateOptions(Builder builder) {
        this.name = builder.name;
        this.apiKey = builder.apiKey;
        this.apiUrl = builder.apiUrl;
        this.timeout = builder.timeout;
    }

    /** Options resolving everything from the environment/defaults. */
    public static CreateOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The sandbox name, or {@code null} for a server-assigned one. */
    public String name() {
        return name;
    }

    /** The explicit API key, or {@code null} to resolve from the environment. */
    public String apiKey() {
        return apiKey;
    }

    /** The explicit API base URL, or {@code null} to resolve from the environment. */
    public String apiUrl() {
        return apiUrl;
    }

    /** The HTTP request timeout (default 120 seconds; spawn is synchronous). */
    public Duration timeout() {
        return timeout;
    }

    /** Builder for {@link CreateOptions}. */
    public static final class Builder {
        private String name;
        private String apiKey;
        private String apiUrl;
        private Duration timeout = Duration.ofSeconds(120);

        private Builder() {
        }

        /** Name for the new sandbox (optional). */
        public Builder name(String name) {
            this.name = name;
            return this;
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

        public CreateOptions build() {
            return new CreateOptions(this);
        }
    }
}
