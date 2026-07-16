package com.xshellz.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * A remote xShellz sandbox: a throwaway, gVisor-isolated Linux box.
 *
 * <p>Control plane over HTTPS (create / list / start / destroy), data plane
 * over SSH as {@code root} (exec + SFTP). Create one with
 * {@link #create(CreateOptions)}, attach to an existing one with
 * {@link #connect(String, String)}, or enumerate them with {@link #list()}.
 *
 * <p>Used with {@code try}-with-resources, the sandbox is destroyed on
 * {@link #close()} unless {@link #detach()} was called:
 *
 * <pre>{@code
 * try (Sandbox sbx = Sandbox.create(CreateOptions.builder().name("demo").build())) {
 *     CommandResult r = sbx.run("echo 42");
 *     System.out.println(r.stdout());
 * } // the box is destroyed here
 * }</pre>
 *
 * <p>An in-memory ed25519 keypair is generated per {@code create()}; the
 * private key never leaves the process and the control plane only sees the
 * public half. SSH host keys are auto-accepted: sandbox host keys are minted
 * at spawn time, so there is no out-of-band fingerprint to pin against.
 */
public final class Sandbox implements AutoCloseable {

    private static final String STATUS_RUNNING = "running";

    private SandboxInfo info;
    private final ApiClient api;
    private final KeyPair keyPair;
    private final String privateKeyOpenSsh;
    private SandboxTransport transport;
    private boolean detached;
    private boolean killed;

    Sandbox(SandboxInfo info, ApiClient api, KeyPair keyPair, String privateKeyOpenSsh,
            SandboxTransport transport) {
        this.info = info;
        this.api = api;
        this.keyPair = keyPair;
        this.privateKeyOpenSsh = privateKeyOpenSsh;
        this.transport = transport;
    }

    // ------------------------------------------------------------------ //
    // Constructors (control plane)
    // ------------------------------------------------------------------ //

    /** Spawns a new sandbox with default options; see {@link #create(CreateOptions)}. */
    public static Sandbox create() {
        return create(CreateOptions.defaults());
    }

    /**
     * Spawns a new sandbox and returns it once it is RUNNING.
     *
     * <p>An in-memory ed25519 keypair is generated for the box; the private
     * key never leaves this process and the server never sees it. Spawning is
     * synchronous - the box is reachable when this returns.
     *
     * @throws AuthException  missing/invalid API key, insufficient scope, or an
     *                        account gate (verification, preview)
     * @throws QuotaException the plan's concurrent sandbox limit is reached or
     *                        the plan has no sandbox entitlement
     * @throws ApiException   other API failures (throttle 429, host capacity 503, ...)
     */
    public static Sandbox create(CreateOptions options) {
        SshKeys.Ed25519KeyPair keys = SshKeys.generate();
        ApiClient api = new ApiClient(options.apiKey(), options.apiUrl(), options.timeout());
        ObjectNode body = ApiClient.newObject();
        body.put("ssh_public_key", keys.publicKeyLine());
        if (options.name() != null) {
            body.put("name", options.name());
        }
        JsonNode payload = api.post("/shells/agent", body);
        return new Sandbox(SandboxInfo.fromJson(payload), api, keys.keyPair(),
                keys.privateKeyOpenSsh(), null);
    }

    /** Attaches to an existing sandbox; see {@link #connect(String, String, ApiOptions)}. */
    public static Sandbox connect(String uuid, String privateKeyOpenSsh) {
        return connect(uuid, privateKeyOpenSsh, ApiOptions.defaults());
    }

    /**
     * Attaches to an existing sandbox by UUID.
     *
     * @param uuid              the sandbox id (see {@link #uuid()} / {@link #list()})
     * @param privateKeyOpenSsh the OpenSSH-format ed25519 private key whose public
     *                          half the box was created with (persist
     *                          {@link #privateKeyOpenSsh()} after {@link #detach()})
     * @throws SandboxNotRunningException the UUID is not among the account's sandboxes
     * @throws XshellzException           the private key cannot be parsed
     */
    public static Sandbox connect(String uuid, String privateKeyOpenSsh, ApiOptions options) {
        SshKeys.Ed25519KeyPair keys = SshKeys.fromOpenSsh(privateKeyOpenSsh);
        ApiClient api = new ApiClient(options.apiKey(), options.apiUrl(), options.timeout());
        SandboxInfo found = find(api, uuid);
        return new Sandbox(found, api, keys.keyPair(), privateKeyOpenSsh, null);
    }

    /** Lists the account's active sandboxes with default options. */
    public static List<SandboxInfo> list() {
        return list(ApiOptions.defaults());
    }

    /** Lists the account's active sandboxes (a bare array on the wire). */
    public static List<SandboxInfo> list(ApiOptions options) {
        ApiClient api = new ApiClient(options.apiKey(), options.apiUrl(), options.timeout());
        JsonNode payload = api.get("/shells/agent");
        List<SandboxInfo> infos = new ArrayList<>();
        if (payload.isArray()) {
            for (JsonNode item : payload) {
                infos.add(SandboxInfo.fromJson(item));
            }
        }
        return infos;
    }

    /** Resolves one sandbox via the list endpoint (there is no GET show route). */
    private static SandboxInfo find(ApiClient api, String uuid) {
        JsonNode payload = api.get("/shells/agent");
        if (payload.isArray()) {
            for (JsonNode item : payload) {
                SandboxInfo candidate = SandboxInfo.fromJson(item);
                if (candidate.uuid().equals(uuid)) {
                    return candidate;
                }
            }
        }
        throw new SandboxNotRunningException(
                "Sandbox " + uuid + " was not found among this account's active sandboxes.");
    }

    // ------------------------------------------------------------------ //
    // Properties
    // ------------------------------------------------------------------ //

    /** The last-known control-plane state of the sandbox. */
    public SandboxInfo info() {
        return info;
    }

    /** The sandbox id. */
    public String uuid() {
        return info.uuid();
    }

    /** The sandbox name. */
    public String name() {
        return info.name();
    }

    /** The sandbox status, e.g. {@code "running"} or {@code "stopped"}. */
    public String status() {
        return info.status();
    }

    /** The SSH host, or {@code null} if unknown. */
    public String sshHost() {
        return info.sshHost();
    }

    /** The SSH port, or {@code null} if unknown. */
    public Integer sshPort() {
        return info.sshPort();
    }

    /** A ready-to-copy {@code ssh -p PORT root@HOST} command, or {@code null}. */
    public String sshCommand() {
        return info.sshCommand();
    }

    /**
     * The OpenSSH serialization of this sandbox's private key. Persist it
     * (together with {@link #uuid()}) to re-attach later from another process
     * via {@link #connect(String, String)}.
     */
    public String privateKeyOpenSsh() {
        return privateKeyOpenSsh;
    }

    // ------------------------------------------------------------------ //
    // Data plane (SSH/SFTP)
    // ------------------------------------------------------------------ //

    /** Runs a shell command with default options; see {@link #run(String, RunOptions)}. */
    public CommandResult run(String command) {
        return run(command, RunOptions.defaults());
    }

    /**
     * Runs a shell command in the sandbox and waits for it to finish.
     *
     * <p>A non-zero exit code does NOT throw - inspect
     * {@link CommandResult#exitCode()}. {@code onStdout}/{@code onStderr}
     * receive decoded output chunks as they arrive (the full output is still
     * returned in the result).
     *
     * @throws SandboxNotRunningException the box is not in the {@code running} state
     * @throws CommandTimeoutException    {@code RunOptions.timeout} elapsed before exit
     */
    public CommandResult run(String command, RunOptions options) {
        SandboxTransport t = getTransport();
        String full = ShellCommand.build(command, options.cwd(), options.env());
        return t.exec(full, options.timeout(), options.onStdout(), options.onStderr());
    }

    /** Writes {@code data} to {@code path} inside the sandbox (SFTP). */
    public void writeFile(String path, byte[] data) {
        getTransport().writeFile(path, data);
    }

    /** Reads and returns the contents of {@code path} inside the sandbox (SFTP). */
    public byte[] readFile(String path) {
        return getTransport().readFile(path);
    }

    /** Uploads a local file into the sandbox (SFTP). */
    public void upload(Path localPath, String remotePath) {
        getTransport().upload(localPath, remotePath);
    }

    /** Downloads a file from the sandbox to a local path (SFTP). */
    public void download(String remotePath, Path localPath) {
        getTransport().download(remotePath, localPath);
    }

    // ------------------------------------------------------------------ //
    // Lifecycle (control plane)
    // ------------------------------------------------------------------ //

    /**
     * Resumes an idle-stopped box ({@code POST /shells/agent/{uuid}/start}).
     *
     * <p>Free-tier boxes idle-stop after ~30 minutes; this brings the same box
     * (same {@code /home}, same authorized key) back to {@code running}.
     *
     * @throws SandboxNotRunningException there is no stopped box to start - it
     *                                    may already be running, suspended, or deleted
     */
    public void start() {
        JsonNode payload;
        try {
            payload = api.post("/shells/agent/" + uuid() + "/start", null);
        } catch (ApiException e) {
            if (e.statusCode() == 404) {
                throw new SandboxNotRunningException(
                        "Sandbox " + uuid() + " has no stopped box to start - it may already "
                                + "be running, suspended, or deleted.", e);
            }
            throw e;
        }
        info = SandboxInfo.fromJson(payload);
        closeTransport();
    }

    /**
     * Destroys the sandbox ({@code DELETE /shells/agent/{uuid}}). Idempotent:
     * a 404 (already gone) is swallowed.
     */
    public void kill() {
        closeTransport();
        if (killed) {
            return;
        }
        try {
            api.delete("/shells/agent/" + uuid());
        } catch (ApiException e) {
            if (e.statusCode() != 404) {
                throw e;
            }
        }
        killed = true;
    }

    /**
     * Keeps the sandbox alive when {@link #close()} runs.
     *
     * <p>Persist {@link #privateKeyOpenSsh()} and {@link #uuid()} to re-attach
     * later with {@link #connect(String, String)}.
     */
    public void detach() {
        detached = true;
    }

    /** Re-fetches this sandbox's state from the control plane. */
    public SandboxInfo refresh() {
        info = find(api, uuid());
        return info;
    }

    /**
     * Destroys the sandbox unless {@link #detach()} was called, then closes
     * the SSH connection. Called automatically by {@code try}-with-resources.
     */
    @Override
    public void close() {
        try {
            if (!detached) {
                kill();
            }
        } finally {
            closeTransport();
        }
    }

    @Override
    public String toString() {
        return "Sandbox[uuid=" + uuid() + ", status=" + status() + ", ssh=" + sshHost() + ":"
                + sshPort() + "]";
    }

    // ------------------------------------------------------------------ //
    // Internals
    // ------------------------------------------------------------------ //

    private SandboxTransport getTransport() {
        if (transport != null) {
            return transport;
        }
        if (!STATUS_RUNNING.equals(status())) {
            throw new SandboxNotRunningException(
                    "Sandbox " + uuid() + " is '" + status() + "', not 'running'. Call start() "
                            + "to resume an idle-stopped box.");
        }
        if (sshHost() == null || sshPort() == null) {
            throw new SandboxNotRunningException(
                    "Sandbox " + uuid() + " has no SSH endpoint yet (host/port unknown).");
        }
        if (keyPair == null) {
            throw new XshellzException(
                    "No private key available for this sandbox - attach with "
                            + "Sandbox.connect(uuid, privateKeyOpenSsh).");
        }
        transport = new SshjTransport(sshHost(), sshPort(), keyPair);
        return transport;
    }

    private void closeTransport() {
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }
}
