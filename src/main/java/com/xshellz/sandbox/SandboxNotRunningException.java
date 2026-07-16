package com.xshellz.sandbox;

/**
 * The sandbox is not in the {@code running} state (or no longer exists).
 */
public class SandboxNotRunningException extends XshellzException {

    public SandboxNotRunningException(String message) {
        super(message);
    }

    public SandboxNotRunningException(String message, Throwable cause) {
        super(message, cause);
    }
}
