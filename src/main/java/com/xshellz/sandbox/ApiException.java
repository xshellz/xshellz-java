package com.xshellz.sandbox;

/**
 * Any other non-success API response (4xx/5xx).
 *
 * <p>Carries the HTTP {@link #statusCode()} and the parsed JSON {@link #body()}
 * (a Jackson {@code JsonNode}) if the response was JSON, else the raw text.
 */
public class ApiException extends XshellzException {

    private final int statusCode;
    private final transient Object body;

    public ApiException(String message, int statusCode, Object body) {
        super(message);
        this.statusCode = statusCode;
        this.body = body;
    }

    /** The HTTP status code of the failed response. */
    public int statusCode() {
        return statusCode;
    }

    /** The parsed JSON body ({@code JsonNode}) if the response was JSON, else the raw text. */
    public Object body() {
        return body;
    }
}
