package com.xshellz.sandbox;

/**
 * The outcome of a single {@link Sandbox#run(String)} invocation.
 *
 * <p>A non-zero {@link #exitCode()} does NOT raise - it is data, exactly like
 * a local subprocess call.
 *
 * @param stdout   the decoded standard output of the command
 * @param stderr   the decoded standard error of the command
 * @param exitCode the process exit status
 */
public record CommandResult(String stdout, String stderr, int exitCode) {

    /** True when the command exited with status 0. */
    public boolean ok() {
        return exitCode == 0;
    }
}
