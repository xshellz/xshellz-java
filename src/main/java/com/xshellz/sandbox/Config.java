package com.xshellz.sandbox;

import java.util.function.Function;

/**
 * Configuration resolution: explicit argument &gt; environment &gt; default.
 */
final class Config {

    static final String DEFAULT_API_URL = "https://api.xshellz.com/v1";
    static final String API_KEY_ENV = "XSHELLZ_API_KEY";
    static final String API_URL_ENV = "XSHELLZ_API_URL";

    private Config() {
    }

    static String resolveApiKey(String explicit) {
        return resolveApiKey(explicit, System::getenv);
    }

    /**
     * Resolves the API key or throws a helpful {@link AuthException}.
     *
     * @param env the environment lookup (injectable for tests)
     */
    static String resolveApiKey(String explicit, Function<String, String> env) {
        String key = firstNonBlank(explicit, env.apply(API_KEY_ENV));
        if (key == null) {
            throw new AuthException(
                    "No xShellz API key found. Pass apiKey(...) in the options or set the "
                            + API_KEY_ENV + " environment variable. Create a personal access "
                            + "token with `read` and `write` scopes from your xShellz dashboard "
                            + "(Settings -> API tokens) or via POST /v1/auth/tokens.");
        }
        return key;
    }

    static String resolveApiUrl(String explicit) {
        return resolveApiUrl(explicit, System::getenv);
    }

    /**
     * Resolves the API base URL (no trailing slash).
     *
     * @param env the environment lookup (injectable for tests)
     */
    static String resolveApiUrl(String explicit, Function<String, String> env) {
        String url = firstNonBlank(explicit, env.apply(API_URL_ENV));
        if (url == null) {
            url = DEFAULT_API_URL;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
