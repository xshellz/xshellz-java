package com.xshellz.sandbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The process view of a running sandbox, as reported by
 * {@code GET /v1/shells/agent/{uuid}/procs}: top processes, active SSH
 * session count, detected agent CLIs, and disk usage.
 *
 * @param procs         top processes by resource usage
 * @param sessions      active SSH session count
 * @param agents        names of detected coding-agent processes (e.g. {@code "claude"})
 * @param diskUsedMb    disk space used, MB
 * @param diskAllowedMb the plan's disk ceiling, MB
 */
public record SandboxProcs(
        List<ProcessInfo> procs,
        int sessions,
        List<String> agents,
        long diskUsedMb,
        long diskAllowedMb) {

    /**
     * One process row.
     *
     * @param pid  the process id
     * @param comm the command name
     * @param cpu  CPU usage percent
     * @param mem  memory usage percent
     */
    public record ProcessInfo(int pid, String comm, double cpu, double mem) {
    }

    /** Builds a {@code SandboxProcs} from the control plane's JSON payload. */
    public static SandboxProcs fromJson(JsonNode payload) {
        List<ProcessInfo> procs = new ArrayList<>();
        for (JsonNode row : payload.path("procs")) {
            procs.add(new ProcessInfo(
                    row.path("pid").asInt(0),
                    row.path("comm").asText(""),
                    row.path("cpu").asDouble(0.0),
                    row.path("mem").asDouble(0.0)));
        }
        List<String> agents = new ArrayList<>();
        for (JsonNode agent : payload.path("agents")) {
            agents.add(agent.asText());
        }
        return new SandboxProcs(
                List.copyOf(procs),
                payload.path("sessions").asInt(0),
                List.copyOf(agents),
                payload.path("disk_used_mb").asLong(0),
                payload.path("disk_allowed_mb").asLong(0));
    }
}
