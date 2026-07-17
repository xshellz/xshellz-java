package com.xshellz.sandbox;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Live resource usage of a running sandbox, plus the plan-allowed ceilings,
 * as reported by {@code GET /v1/shells/agent/{uuid}/stats} (snake_case wire
 * shape mirrored field by field).
 *
 * @param memUsedMb           memory currently used, MB
 * @param memLimitMb          the box's memory limit, MB
 * @param memAllowedMb        the plan's memory ceiling, MB
 * @param cpuPercent          current CPU usage in percent (100 = one full vCPU)
 * @param cpuAllowedVcpus     the plan's CPU ceiling in vCPUs
 * @param cpuThrottledPeriods cgroup CPU throttle events since boot
 * @param pidsCurrent         processes currently running
 * @param pidsAllowed         the plan's process-count ceiling
 * @param diskUsedMb          disk space used, MB
 * @param diskAllowedMb       the plan's disk ceiling, MB
 * @param netRxMb             network bytes received since boot, MB
 * @param netTxMb             network bytes sent since boot, MB
 * @param blkReadMb           block-device bytes read since boot, MB
 * @param blkWriteMb          block-device bytes written since boot, MB
 */
public record SandboxStats(
        long memUsedMb,
        long memLimitMb,
        long memAllowedMb,
        double cpuPercent,
        double cpuAllowedVcpus,
        long cpuThrottledPeriods,
        long pidsCurrent,
        long pidsAllowed,
        long diskUsedMb,
        long diskAllowedMb,
        long netRxMb,
        long netTxMb,
        long blkReadMb,
        long blkWriteMb) {

    /** Builds a {@code SandboxStats} from the control plane's JSON payload. */
    public static SandboxStats fromJson(JsonNode payload) {
        return new SandboxStats(
                payload.path("mem_used_mb").asLong(0),
                payload.path("mem_limit_mb").asLong(0),
                payload.path("mem_allowed_mb").asLong(0),
                payload.path("cpu_percent").asDouble(0.0),
                payload.path("cpu_allowed_vcpus").asDouble(0.0),
                payload.path("cpu_throttled_periods").asLong(0),
                payload.path("pids_current").asLong(0),
                payload.path("pids_allowed").asLong(0),
                payload.path("disk_used_mb").asLong(0),
                payload.path("disk_allowed_mb").asLong(0),
                payload.path("net_rx_mb").asLong(0),
                payload.path("net_tx_mb").asLong(0),
                payload.path("blk_read_mb").asLong(0),
                payload.path("blk_write_mb").asLong(0));
    }
}
