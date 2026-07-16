package com.xshellz.sandbox;

/**
 * Base class for every exception thrown by the xShellz SDK.
 *
 * <p>All SDK exceptions are unchecked so that {@code try}-with-resources usage
 * of {@link Sandbox} stays ergonomic.
 */
public class XshellzException extends RuntimeException {

    public XshellzException(String message) {
        super(message);
    }

    public XshellzException(String message, Throwable cause) {
        super(message, cause);
    }
}
