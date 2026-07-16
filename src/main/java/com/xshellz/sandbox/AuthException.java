package com.xshellz.sandbox;

/**
 * Authentication or authorization failed (HTTP 401/403).
 *
 * <p>Thrown for a missing/invalid API key, insufficient token scopes, account
 * verification requirements, and other access gates.
 */
public class AuthException extends XshellzException {

    public AuthException(String message) {
        super(message);
    }
}
