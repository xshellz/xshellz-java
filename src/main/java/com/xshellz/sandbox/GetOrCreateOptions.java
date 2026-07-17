package com.xshellz.sandbox;

import java.time.Duration;

/**
 * Options for {@link Sandbox#getOrCreate(String, GetOrCreateOptions)}.
 *
 * <p>By default a local {@link Keystore} at {@code ~/.xshellz/keys/} is used
 * to persist the private key of a newly created box and to find the key of an
 * existing one. An explicit {@link #privateKey()} always wins over the
 * keystore. Disabling the keystore ({@link Builder#disableKeystore()}) makes
 * {@code getOrCreate} create-only-or-error: attaching to an existing box then
 * requires an explicit private key.
 *
 * <p>Config precedence for the API settings: explicit option &gt;
 * {@code XSHELLZ_API_KEY} / {@code XSHELLZ_API_URL} environment variables &gt;
 * default ({@code https://api.xshellz.com/v1}).
 */
public final class GetOrCreateOptions {

    private final String apiKey;
    private final String apiUrl;
    private final Duration timeout;
    private final String privateKey;
    private final Keystore keystore;

    private GetOrCreateOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiUrl = builder.apiUrl;
        this.timeout = builder.timeout;
        this.privateKey = builder.privateKey;
        this.keystore = builder.keystore;
    }

    /** Options resolving everything from the environment/defaults. */
    public static GetOrCreateOptions defaults() {
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

    /** An explicit OpenSSH private key for the existing box, or {@code null}. */
    public String privateKey() {
        return privateKey;
    }

    /** The keystore to persist/load keys with, or {@code null} when disabled. */
    public Keystore keystore() {
        return keystore;
    }

    /** Builder for {@link GetOrCreateOptions}. */
    public static final class Builder {
        private String apiKey;
        private String apiUrl;
        private Duration timeout = Duration.ofSeconds(120);
        private String privateKey;
        private Keystore keystore = Keystore.defaultKeystore();

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

        /**
         * The OpenSSH ed25519 private key of the existing box. Wins over any
         * keystore lookup when the box is found.
         */
        public Builder privateKey(String privateKeyOpenSsh) {
            this.privateKey = privateKeyOpenSsh;
            return this;
        }

        /** Use a custom keystore instead of the default {@code ~/.xshellz/keys/}. */
        public Builder keystore(Keystore keystore) {
            if (keystore == null) {
                throw new XshellzException(
                        "keystore(null) is ambiguous - call disableKeystore() to opt out.");
            }
            this.keystore = keystore;
            return this;
        }

        /**
         * Never touch the local disk: no key is persisted on create, and an
         * existing box can only be attached with an explicit {@link #privateKey}.
         */
        public Builder disableKeystore() {
            this.keystore = null;
            return this;
        }

        public GetOrCreateOptions build() {
            return new GetOrCreateOptions(this);
        }
    }
}
