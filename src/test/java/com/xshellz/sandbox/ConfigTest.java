package com.xshellz.sandbox;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config precedence: explicit argument > environment > default. */
class ConfigTest {

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    private static final Function<String, String> EMPTY_ENV = env(Map.of());

    @Test
    void missingApiKeyThrowsHelpfulAuthException() {
        AuthException e = assertThrows(AuthException.class,
                () -> Config.resolveApiKey(null, EMPTY_ENV));
        assertTrue(e.getMessage().contains("XSHELLZ_API_KEY"));
        assertTrue(e.getMessage().contains("POST /v1/auth/tokens"));
    }

    @Test
    void blankApiKeyIsTreatedAsMissing() {
        assertThrows(AuthException.class, () -> Config.resolveApiKey("   ", EMPTY_ENV));
    }

    @Test
    void apiKeyReadFromEnvWhenNotExplicit() {
        assertEquals("env-key",
                Config.resolveApiKey(null, env(Map.of("XSHELLZ_API_KEY", "env-key"))));
    }

    @Test
    void explicitApiKeyBeatsEnv() {
        assertEquals("arg-key",
                Config.resolveApiKey("arg-key", env(Map.of("XSHELLZ_API_KEY", "env-key"))));
    }

    @Test
    void apiUrlDefaultsToProduction() {
        assertEquals("https://api.xshellz.com/v1", Config.resolveApiUrl(null, EMPTY_ENV));
    }

    @Test
    void apiUrlReadFromEnvAndExplicitBeatsEnv() {
        Function<String, String> withEnv = env(Map.of("XSHELLZ_API_URL", "https://env-host.example/v1"));
        assertEquals("https://env-host.example/v1", Config.resolveApiUrl(null, withEnv));
        assertEquals("https://arg-host.example/v1",
                Config.resolveApiUrl("https://arg-host.example/v1", withEnv));
    }

    @Test
    void apiUrlTrailingSlashIsStripped() {
        assertEquals("https://api.staging.example/v1",
                Config.resolveApiUrl("https://api.staging.example/v1/", EMPTY_ENV));
    }
}
