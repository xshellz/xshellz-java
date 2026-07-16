package com.xshellz.sandbox;

/**
 * A command executed with {@code RunOptions.timeout(...)} exceeded its deadline.
 */
public class CommandTimeoutException extends XshellzException {

    public CommandTimeoutException(String message) {
        super(message);
    }

    public CommandTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
