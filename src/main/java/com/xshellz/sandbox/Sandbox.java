package com.xshellz.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static final String JOBS_DIR = "$HOME/.xshellz/jobs";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Interpreter + file extension per {@link #runCode(String, String)} language. */
    private static final Map<String, String[]> LANGUAGES = Map.of(
            "python", new String[] {"python3", "py"},
            "node", new String[] {"node", "js"},
            "bash", new String[] {"bash", "sh"},
            "ruby", new String[] {"ruby", "rb"},
            "php", new String[] {"php", "php"});

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

    /** Gets or creates the named sandbox with default options; see {@link #getOrCreate(String, GetOrCreateOptions)}. */
    public static Sandbox getOrCreate(String name) {
        return getOrCreate(name, GetOrCreateOptions.defaults());
    }

    /**
     * Attaches to the sandbox with exactly this name, creating it if it does
     * not exist - the "permanent named box" pattern.
     *
     * <ul>
     *   <li><b>Not found:</b> the box is created with {@code name} and, unless
     *       the keystore is disabled, its private key is persisted to the
     *       keystore (default {@code ~/.xshellz/keys/<name>.key}, 0600).</li>
     *   <li><b>Found:</b> the private key is taken from
     *       {@link GetOrCreateOptions.Builder#privateKey(String)} if given,
     *       else from the keystore. If the box is idle-stopped it is
     *       {@link #start()}ed first.</li>
     * </ul>
     *
     * <p>The returned sandbox is already {@link #detach()}ed: closing it keeps
     * the box alive (that is the point of a permanent box). Call {@link #kill()}
     * to destroy it for real.
     *
     * @throws MissingKeyException the box exists but no private key was found
     *                             (the message says where one was expected)
     * @throws AuthException       missing/invalid API key or an account gate
     * @throws QuotaException      creating would exceed the plan's sandbox limit
     */
    public static Sandbox getOrCreate(String name, GetOrCreateOptions options) {
        if (name == null || name.isBlank()) {
            throw new XshellzException("getOrCreate requires a non-blank sandbox name.");
        }
        ApiClient api = new ApiClient(options.apiKey(), options.apiUrl(), options.timeout());
        Keystore keystore = options.keystore();

        SandboxInfo found = null;
        JsonNode listing = api.get("/shells/agent");
        if (listing.isArray()) {
            for (JsonNode item : listing) {
                SandboxInfo candidate = SandboxInfo.fromJson(item);
                if (name.equals(candidate.name())) {
                    found = candidate;
                    break;
                }
            }
        }

        if (found == null) {
            SshKeys.Ed25519KeyPair keys = SshKeys.generate();
            ObjectNode body = ApiClient.newObject();
            body.put("ssh_public_key", keys.publicKeyLine());
            body.put("name", name);
            SandboxInfo created = SandboxInfo.fromJson(api.post("/shells/agent", body));
            if (keystore != null) {
                keystore.save(name, keys.privateKeyOpenSsh());
            }
            Sandbox sandbox = new Sandbox(created, api, keys.keyPair(), keys.privateKeyOpenSsh(), null);
            sandbox.detach();
            return sandbox;
        }

        String privateKey = options.privateKey();
        if (privateKey == null && keystore != null) {
            privateKey = keystore.load(name);
        }
        if (privateKey == null) {
            String where = keystore != null
                    ? "no key file at " + keystore.pathFor(name)
                    : "the keystore is disabled, so pass GetOrCreateOptions.builder().privateKey(...)";
            throw new MissingKeyException(
                    "Sandbox '" + name + "' already exists but no private key for it was found ("
                            + where + "). Without the key the SDK cannot SSH into the box.");
        }
        SshKeys.Ed25519KeyPair keys = SshKeys.fromOpenSsh(privateKey);
        Sandbox sandbox = new Sandbox(found, api, keys.keyPair(), privateKey, null);
        sandbox.detach();
        if (!STATUS_RUNNING.equals(sandbox.status())) {
            sandbox.start();
        }
        return sandbox;
    }

    /** Reads the account boxfile with default options; see {@link #getBoxfile(ApiOptions)}. */
    public static String getBoxfile() {
        return getBoxfile(ApiOptions.defaults());
    }

    /**
     * Reads the account's saved {@code xshellz.box} manifest
     * ({@code GET /shells/agent/boxfile}), or {@code null} if none is saved.
     *
     * <p>The boxfile is a provisioning template: it is seeded into
     * {@code ~/xshellz.box} on every NEW box the account creates (preinstall
     * packages, etc.). Changing it does not affect boxes that already exist.
     */
    public static String getBoxfile(ApiOptions options) {
        ApiClient api = new ApiClient(options.apiKey(), options.apiUrl(), options.timeout());
        return textOrNull(api.get("/shells/agent/boxfile"), "manifest");
    }

    /** Saves the account boxfile with default options; see {@link #setBoxfile(String, ApiOptions)}. */
    public static String setBoxfile(String manifest) {
        return setBoxfile(manifest, ApiOptions.defaults());
    }

    /**
     * Saves (or clears, with {@code null}/blank) the account's
     * {@code xshellz.box} manifest ({@code PUT /shells/agent/boxfile}) and
     * returns the manifest as stored. Applies to the NEXT box created; max
     * 16 KiB.
     */
    public static String setBoxfile(String manifest, ApiOptions options) {
        ApiClient api = new ApiClient(options.apiKey(), options.apiUrl(), options.timeout());
        ObjectNode body = ApiClient.newObject();
        if (manifest == null) {
            body.putNull("manifest");
        } else {
            body.put("manifest", manifest);
        }
        return textOrNull(api.put("/shells/agent/boxfile", body), "manifest");
    }

    private static String textOrNull(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
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

    /** Spawns a background job with a generated id; see {@link #spawn(String, String)}. */
    public JobHandle spawn(String command) {
        return spawn(command, null);
    }

    /**
     * Starts {@code command} as a detached background process ({@code nohup})
     * and returns immediately with a {@link JobHandle}.
     *
     * <p>The job's stdout+stderr are redirected to
     * {@code ~/.xshellz/jobs/<job-id>.log} inside the box; its PID is captured
     * (and recorded in a sibling {@code .pid} file so {@link #jobs()} can list
     * liveness later). The job survives SSH disconnects and your process
     * exiting - it dies only with the box (or {@link JobHandle#stop()}).
     *
     * @param command the shell command to run
     * @param name    optional job-id prefix (sanitized); {@code null} for {@code "job"}
     */
    public JobHandle spawn(String command, String name) {
        String id = jobId(name);
        String logPath = JOBS_DIR + "/" + id + ".log";
        String pidPath = JOBS_DIR + "/" + id + ".pid";
        CommandResult result = run("mkdir -p " + JOBS_DIR + " && nohup bash -c "
                + ShellCommand.quote(command) + " > " + logPath + " 2>&1 < /dev/null"
                + " & echo $! | tee " + pidPath);
        String pidText = result.stdout().trim();
        long pid;
        try {
            pid = Long.parseLong(pidText);
        } catch (NumberFormatException e) {
            throw new XshellzException("spawn failed to start the job (no PID captured). "
                    + "stdout: '" + pidText + "', stderr: '" + result.stderr().trim() + "'");
        }
        return new JobHandle(this, id, pid, logPath);
    }

    /**
     * Lists the box's background jobs (everything ever {@link #spawn}ed whose
     * log/pid files still exist under {@code ~/.xshellz/jobs/}) with their
     * current liveness.
     */
    public List<JobInfo> jobs() {
        CommandResult result = run("cd " + JOBS_DIR + " 2>/dev/null || exit 0"
                + "; for f in *.pid; do [ -e \"$f\" ] || exit 0"
                + "; pid=$(cat \"$f\")"
                + "; if kill -0 \"$pid\" 2>/dev/null; then s=running; else s=dead; fi"
                + "; echo \"${f%.pid} $pid $s\"; done");
        List<JobInfo> jobs = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3) {
                continue;
            }
            long pid;
            try {
                pid = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            jobs.add(new JobInfo(parts[0], pid, "running".equals(parts[2]),
                    JOBS_DIR + "/" + parts[0] + ".log"));
        }
        return jobs;
    }

    /** Runs a code snippet with default options; see {@link #runCode(String, String, RunOptions)}. */
    public CommandResult runCode(String language, String code) {
        return runCode(language, code, RunOptions.defaults());
    }

    /**
     * Runs a code snippet through its interpreter - the safe way to execute
     * AI-generated code: the snippet is written to a temp file inside the box
     * (no shell-quoting pitfalls), executed, and the temp file is always
     * deleted afterwards.
     *
     * <p>Supported languages: {@code python} (runs {@code python3}),
     * {@code node}, {@code bash}, {@code ruby}, {@code php}. The interpreter
     * must be installed in the box ({@code python3} and {@code bash} are
     * preinstalled; install others via {@link #run(String)} or the boxfile).
     *
     * @throws UnsupportedLanguageException the language is not in the list above
     */
    public CommandResult runCode(String language, String code, RunOptions options) {
        String[] spec = language == null ? null : LANGUAGES.get(language.toLowerCase(Locale.ROOT));
        if (spec == null) {
            throw new UnsupportedLanguageException("Unsupported runCode language: '" + language
                    + "'. Supported: bash, node, php, python, ruby.");
        }
        String path = "/tmp/xshellz-code-" + randomSuffix() + "." + spec[1];
        writeFile(path, code.getBytes(StandardCharsets.UTF_8));
        try {
            return run(spec[0] + " " + path, options);
        } finally {
            try {
                run("rm -f " + path);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup; the original result/exception wins.
            }
        }
    }

    private static String jobId(String name) {
        String prefix = name == null || name.isBlank() ? "job" : Keystore.sanitize(name.trim());
        return prefix + "-" + randomSuffix();
    }

    private static String randomSuffix() {
        return String.format("%06x", RANDOM.nextInt(0x1000000));
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
     * Reboots a running box ({@code POST /shells/agent/{uuid}/restart}): the
     * entrypoint re-runs, {@code /home} is preserved, processes die. The SSH
     * connection is re-established on the next data-plane call.
     */
    public void restart() {
        info = SandboxInfo.fromJson(api.post("/shells/agent/" + uuid() + "/restart", null));
        closeTransport();
    }

    /**
     * Live resource usage ({@code GET /shells/agent/{uuid}/stats}): memory,
     * CPU, process count, disk, network and block IO, together with the
     * plan-allowed ceilings. The box must be running.
     */
    public SandboxStats stats() {
        return SandboxStats.fromJson(api.get("/shells/agent/" + uuid() + "/stats"));
    }

    /**
     * Top processes, active SSH session count, and disk usage
     * ({@code GET /shells/agent/{uuid}/procs}). The box must be running.
     */
    public SandboxProcs procs() {
        return SandboxProcs.fromJson(api.get("/shells/agent/" + uuid() + "/procs"));
    }

    /**
     * Mints a fresh signed URL to the box's browser terminal
     * ({@code GET /shells/agent/{uuid}/terminal}). The URL embeds a short-lived
     * HMAC token valid for about 1 hour - call again for a fresh one rather
     * than storing it.
     */
    public String terminalUrl() {
        return api.get("/shells/agent/" + uuid() + "/terminal").path("url").asText();
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
