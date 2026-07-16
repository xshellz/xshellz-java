package com.xshellz.sandbox;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A sandbox as reported by the control plane (snake_case wire shape).
 *
 * <p>Deserialization is tolerant of missing keys so a newer/older API version
 * never breaks the SDK.
 *
 * @param uuid                the sandbox id
 * @param name                the sandbox name
 * @param status              e.g. {@code "running"}, {@code "stopped"}
 * @param sshCommand          ready-to-copy {@code ssh -p PORT root@HOST} line (nullable)
 * @param sshHost             the SSH host (nullable until known)
 * @param sshPort             the SSH port (nullable until known)
 * @param webTerminalReady    whether the hosted web terminal is reachable
 * @param alwaysOn            whether the box is exempt from idle-stop
 * @param trialHoursRemaining remaining metered always-on trial hours
 * @param spawnedAt           ISO-8601 spawn timestamp (nullable)
 * @param createdAt           ISO-8601 creation timestamp (nullable)
 * @param isolation           the isolation runtime, e.g. {@code "runsc"} (nullable)
 * @param gvisor              whether the box runs under gVisor kernel isolation
 */
public record SandboxInfo(
        String uuid,
        String name,
        String status,
        String sshCommand,
        String sshHost,
        Integer sshPort,
        boolean webTerminalReady,
        boolean alwaysOn,
        double trialHoursRemaining,
        String spawnedAt,
        String createdAt,
        String isolation,
        boolean gvisor) {

    /** Builds a {@code SandboxInfo} from the control plane's JSON payload. */
    public static SandboxInfo fromJson(JsonNode payload) {
        JsonNode port = payload.path("ssh_port");
        return new SandboxInfo(
                payload.path("uuid").asText(""),
                payload.path("name").asText(""),
                payload.path("status").asText(""),
                textOrNull(payload, "ssh_command"),
                textOrNull(payload, "ssh_host"),
                port.isNumber() || port.isTextual() ? port.asInt() : null,
                payload.path("web_terminal_ready").asBoolean(false),
                payload.path("always_on").asBoolean(false),
                payload.path("trial_hours_remaining").asDouble(0.0),
                textOrNull(payload, "spawned_at"),
                textOrNull(payload, "created_at"),
                textOrNull(payload, "isolation"),
                payload.path("gvisor").asBoolean(false));
    }

    private static String textOrNull(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
