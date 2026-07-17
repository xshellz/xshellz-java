package com.xshellz.sandbox;

/**
 * A background process started with {@link Sandbox#spawn(String, String)}.
 *
 * <p>The job runs detached ({@code nohup}) inside the sandbox, so it survives
 * this handle, the SSH connection, and your process - as long as the box
 * itself keeps running. Its output is redirected to {@link #logPath()}.
 */
public final class JobHandle {

    /** Seconds granted between SIGTERM and SIGKILL in {@link #stop()}. */
    static final int STOP_GRACE_SECONDS = 5;

    private final Sandbox sandbox;
    private final String id;
    private final long pid;
    private final String logPath;

    JobHandle(Sandbox sandbox, String id, long pid, String logPath) {
        this.sandbox = sandbox;
        this.id = id;
        this.pid = pid;
        this.logPath = logPath;
    }

    /** The job id (the log/pid file basename), e.g. {@code "worker-3f9a2c"}. */
    public String id() {
        return id;
    }

    /** The PID of the spawned process inside the sandbox. */
    public long pid() {
        return pid;
    }

    /** Path of the combined stdout+stderr log file inside the sandbox. */
    public String logPath() {
        return logPath;
    }

    /** Whether the process is still alive ({@code kill -0} probe). */
    public boolean isRunning() {
        return sandbox.run("kill -0 " + pid + " 2>/dev/null").ok();
    }

    /** The last 100 log lines; see {@link #logs(int)}. */
    public String logs() {
        return logs(100);
    }

    /** The last {@code tailLines} lines of the job's log file. */
    public String logs(int tailLines) {
        return sandbox.run("tail -n " + tailLines + " " + logPath + " 2>/dev/null").stdout();
    }

    /**
     * Stops the job: SIGTERM first, then SIGKILL if it is still alive after a
     * {@value #STOP_GRACE_SECONDS}-second grace period. Idempotent - stopping
     * an already-dead job is a no-op.
     */
    public void stop() {
        sandbox.run("kill -TERM " + pid + " 2>/dev/null || true"
                + "; for i in $(seq 1 " + (STOP_GRACE_SECONDS * 5) + ")"
                + "; do kill -0 " + pid + " 2>/dev/null || exit 0; sleep 0.2; done"
                + "; kill -KILL " + pid + " 2>/dev/null || true");
    }

    @Override
    public String toString() {
        return "JobHandle[id=" + id + ", pid=" + pid + ", log=" + logPath + "]";
    }
}
