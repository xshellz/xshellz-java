package com.xshellz.sandbox;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Options for {@link Sandbox#run(String, RunOptions)}.
 */
public final class RunOptions {

    private final String cwd;
    private final Map<String, String> env;
    private final Duration timeout;
    private final Consumer<String> onStdout;
    private final Consumer<String> onStderr;

    private RunOptions(Builder builder) {
        this.cwd = builder.cwd;
        this.env = builder.env;
        this.timeout = builder.timeout;
        this.onStdout = builder.onStdout;
        this.onStderr = builder.onStderr;
    }

    /** Default options: no cwd/env wrapping, no timeout, no streaming. */
    public static RunOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Working directory for the command, or {@code null}. */
    public String cwd() {
        return cwd;
    }

    /** Environment variables exported before the command, or {@code null}. */
    public Map<String, String> env() {
        return env;
    }

    /** Deadline for the command, or {@code null} to wait indefinitely. */
    public Duration timeout() {
        return timeout;
    }

    /** Callback receiving decoded stdout chunks as they arrive, or {@code null}. */
    public Consumer<String> onStdout() {
        return onStdout;
    }

    /** Callback receiving decoded stderr chunks as they arrive, or {@code null}. */
    public Consumer<String> onStderr() {
        return onStderr;
    }

    /** Builder for {@link RunOptions}. */
    public static final class Builder {
        private String cwd;
        private Map<String, String> env;
        private Duration timeout;
        private Consumer<String> onStdout;
        private Consumer<String> onStderr;

        private Builder() {
        }

        /** Run the command from this working directory. */
        public Builder cwd(String cwd) {
            this.cwd = cwd;
            return this;
        }

        /** Export these environment variables in the remote shell first. */
        public Builder env(Map<String, String> env) {
            this.env = env == null ? null : new LinkedHashMap<>(env);
            return this;
        }

        /** Fail with {@link CommandTimeoutException} if the command outlives this. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Stream decoded stdout chunks as they arrive (full output still returned). */
        public Builder onStdout(Consumer<String> onStdout) {
            this.onStdout = onStdout;
            return this;
        }

        /** Stream decoded stderr chunks as they arrive (full output still returned). */
        public Builder onStderr(Consumer<String> onStderr) {
            this.onStderr = onStderr;
            return this;
        }

        public RunOptions build() {
            return new RunOptions(this);
        }
    }
}
