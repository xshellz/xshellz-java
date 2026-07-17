package com.xshellz.sandbox;

/**
 * One entry of {@link Sandbox#jobs()}: a background job's id, PID, liveness,
 * and log file path inside the sandbox.
 *
 * @param id      the job id (log/pid file basename)
 * @param pid     the recorded PID of the job's process
 * @param running whether the process is currently alive ({@code kill -0})
 * @param logPath path of the job's combined stdout+stderr log file
 */
public record JobInfo(String id, long pid, boolean running, String logPath) {
}
