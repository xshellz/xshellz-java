# xshellz Java SDK - API reference

Package: `com.xshellz.sandbox`. All exceptions are unchecked and extend
`XshellzException` (see [Errors](#errors)).

## Sandbox

The central class: one instance = one remote box. `AutoCloseable` - inside
`try`-with-resources the box is destroyed on exit unless `detach()` was called
(boxes from `getOrCreate` are pre-detached).

### Static constructors (control plane)

| Method | Returns | Description |
|---|---|---|
| `Sandbox.create()` | `Sandbox` | Spawns a new box with default options. |
| `Sandbox.create(CreateOptions)` | `Sandbox` | Spawns a new box; returns once it is `running`. Generates an in-memory ed25519 keypair; only the public half is sent to the server. Throws `AuthException`, `QuotaException`, `ApiException`. |
| `Sandbox.connect(String uuid, String privateKeyOpenSsh)` | `Sandbox` | Attaches to an existing box by UUID with its OpenSSH private key. Throws `SandboxNotRunningException` if the UUID is not among the account's boxes. |
| `Sandbox.connect(String, String, ApiOptions)` | `Sandbox` | Same, with explicit API options. |
| `Sandbox.getOrCreate(String name)` | `Sandbox` | Attach-or-create by exact name; see below. |
| `Sandbox.getOrCreate(String name, GetOrCreateOptions)` | `Sandbox` | Same, with options. |
| `Sandbox.list()` / `Sandbox.list(ApiOptions)` | `List<SandboxInfo>` | The account's active sandboxes. |
| `Sandbox.getBoxfile()` / `getBoxfile(ApiOptions)` | `String` (nullable) | The account's saved `xshellz.box` manifest, or `null` if none. Applied to every NEW box at provisioning time. |
| `Sandbox.setBoxfile(String manifest)` / `setBoxfile(String, ApiOptions)` | `String` (nullable) | Saves the manifest (`null`/blank clears it; max 16 KiB) and returns it as stored. Affects the next box created, not existing ones. |

#### `getOrCreate(name, options)` semantics

1. Lists the account's boxes and looks for an exact `name` match.
2. **Not found:** creates the box with that name and, unless the keystore is
   disabled, saves the generated private key to the keystore
   (`~/.xshellz/keys/<sanitized-name>.key`, permissions `0600`).
3. **Found:** resolves the private key - explicit
   `GetOrCreateOptions.privateKey(...)` wins, else keystore lookup. No key ->
   `MissingKeyException` (the message says where a key was expected). If the
   box is idle-stopped, `start()` is called and the info refreshed.
4. The returned sandbox is already `detach()`ed: `close()` keeps it alive.
   Call `kill()` to destroy it.

### Instance properties

| Method | Returns | Description |
|---|---|---|
| `info()` | `SandboxInfo` | Last-known control-plane state. |
| `uuid()` | `String` | The sandbox id. |
| `name()` | `String` | The sandbox name. |
| `status()` | `String` | `"running"`, `"stopped"`, ... |
| `sshHost()` / `sshPort()` | `String` / `Integer` | SSH endpoint (nullable until known). |
| `sshCommand()` | `String` | Ready-to-copy `ssh -p PORT root@HOST` line. |
| `privateKeyOpenSsh()` | `String` | OpenSSH serialization of the box's private key - persist with `uuid()` to re-attach later. |

### Commands and code

| Method | Returns | Description |
|---|---|---|
| `run(String command)` | `CommandResult` | Runs a shell command, waits for exit. Non-zero exit does NOT throw. |
| `run(String, RunOptions)` | `CommandResult` | Adds `cwd`, `env`, `timeout` (-> `CommandTimeoutException`), and `onStdout`/`onStderr` streaming callbacks. |
| `runCode(String language, String code)` | `CommandResult` | Writes `code` to a temp file in the box, runs the interpreter, always deletes the file. Languages: `python` (python3), `node`, `bash`, `ruby`, `php`. Unknown -> `UnsupportedLanguageException`. |
| `runCode(String, String, RunOptions)` | `CommandResult` | Same, with run options (applied to the interpreter invocation). |

### Background jobs

| Method | Returns | Description |
|---|---|---|
| `spawn(String command)` | `JobHandle` | Starts the command detached (`nohup`), output to `~/.xshellz/jobs/<id>.log`, PID recorded in `<id>.pid`. Returns immediately. |
| `spawn(String command, String name)` | `JobHandle` | Same with a sanitized `name` prefix on the job id. |
| `jobs()` | `List<JobInfo>` | Every job whose log/pid files still exist, with current liveness. |

### Files (SFTP)

| Method | Description |
|---|---|
| `writeFile(String path, byte[] data)` | Write bytes to a path inside the box. |
| `readFile(String path)` -> `byte[]` | Read a file from the box. |
| `upload(Path localPath, String remotePath)` | Upload a local file. |
| `download(String remotePath, Path localPath)` | Download to a local file. |

### Lifecycle and introspection

| Method | Returns | Description |
|---|---|---|
| `start()` | `void` | Resumes an idle-stopped box (`POST /{uuid}/start`). 404 -> `SandboxNotRunningException`. |
| `restart()` | `void` | Reboots a running box (`POST /{uuid}/restart`): entrypoint re-runs, `/home` preserved, processes die. |
| `stats()` | `SandboxStats` | Live mem/cpu/pids/disk/net/blk usage + plan ceilings (`GET /{uuid}/stats`). Box must be running. |
| `procs()` | `SandboxProcs` | Top processes, SSH session count, agents, disk (`GET /{uuid}/procs`). |
| `terminalUrl()` | `String` | Fresh signed browser-terminal URL (`GET /{uuid}/terminal`). Token valid ~1 hour; mint per use, don't store. |
| `kill()` | `void` | Destroys the box (`DELETE /{uuid}`); idempotent. |
| `detach()` | `void` | Keep the box alive when `close()` runs. |
| `refresh()` | `SandboxInfo` | Re-fetches state from the control plane. |
| `close()` | `void` | `kill()` unless detached, then closes SSH. |

## JobHandle

Returned by `spawn`. The job survives disconnects and your process; it dies
with the box or via `stop()`.

| Member | Returns | Description |
|---|---|---|
| `id()` | `String` | Job id, e.g. `"worker-3f9a2c"` (log/pid file basename). |
| `pid()` | `long` | PID inside the box. |
| `logPath()` | `String` | Combined stdout+stderr log file path in the box. |
| `isRunning()` | `boolean` | `kill -0` probe. |
| `logs()` / `logs(int tailLines)` | `String` | Tail of the log file (default 100 lines). |
| `stop()` | `void` | SIGTERM, then SIGKILL after a 5-second grace. Idempotent. |

## JobInfo

Record returned by `Sandbox.jobs()`: `id()`, `pid()`, `running()`, `logPath()`.

## Keystore

Local directory of sandbox private keys used by `getOrCreate`.

| Member | Description |
|---|---|
| `Keystore.defaultDirectory()` | `~/.xshellz/keys/` (via the `user.home` system property). |
| `Keystore.defaultKeystore()` | Keystore at the default directory. |
| `Keystore.at(Path)` | Keystore at a custom directory. |
| `directory()` / `pathFor(String name)` | Where a given name's key lives (`<sanitized>.key`). |
| `save(String name, String privateKeyOpenSsh)` | Persist a key (dir `0700`, file `0600` on POSIX). |
| `load(String name)` | The stored key, or `null`. |
| `delete(String name)` | Remove a key; returns whether a file existed. |

**Security:** keys are plaintext on disk, readable only by your user on POSIX
filesystems. Deleting a key file revokes that machine's access to the box.

## Result and info records

### CommandResult

`stdout()`, `stderr()`, `exitCode()`, `ok()` (exit == 0).

### SandboxInfo

Wire mirror of one list/create/start response (snake_case -> camelCase):
`uuid()`, `name()`, `status()`, `sshCommand()`, `sshHost()`, `sshPort()`,
`webTerminalReady()`, `alwaysOn()`, `trialHoursRemaining()`, `spawnedAt()`,
`createdAt()`, `isolation()`, `gvisor()`.

### SandboxStats

Wire mirror of `GET /{uuid}/stats`: `memUsedMb()`, `memLimitMb()`,
`memAllowedMb()`, `cpuPercent()`, `cpuAllowedVcpus()`,
`cpuThrottledPeriods()`, `pidsCurrent()`, `pidsAllowed()`, `diskUsedMb()`,
`diskAllowedMb()`, `netRxMb()`, `netTxMb()`, `blkReadMb()`, `blkWriteMb()`.
`*AllowedMb`/`*AllowedVcpus`/`pidsAllowed` are the plan ceilings.

### SandboxProcs

Wire mirror of `GET /{uuid}/procs`: `procs()` (list of
`ProcessInfo(pid, comm, cpu, mem)`), `sessions()` (active SSH sessions),
`agents()` (detected coding-agent process names), `diskUsedMb()`,
`diskAllowedMb()`.

## Options builders

### CreateOptions

`CreateOptions.builder()` -> `name(String)`, `apiKey(String)`,
`apiUrl(String)`, `timeout(Duration)` (default 120s) -> `build()`.

### ApiOptions

Same minus `name` - used by `connect`, `list`, and the boxfile statics.

### GetOrCreateOptions

`GetOrCreateOptions.builder()` -> `apiKey`, `apiUrl`, `timeout`,
`privateKey(String)` (explicit key for an existing box; wins over the
keystore), `keystore(Keystore)` (custom location),
`disableKeystore()` (create-only-or-error mode) -> `build()`.

### RunOptions

`RunOptions.builder()` -> `cwd(String)`, `env(Map<String,String>)`,
`timeout(Duration)`, `onStdout(Consumer<String>)`,
`onStderr(Consumer<String>)` -> `build()`.

## Errors

| Exception | Extends | Thrown when |
|---|---|---|
| `XshellzException` | `RuntimeException` | Base class; also transport/JSON failures. |
| `AuthException` | `XshellzException` | 401/403: bad or missing token, scopes, verification gates. |
| `QuotaException` | `XshellzException` | Plan sandbox limit reached / no entitlement. |
| `SandboxNotRunningException` | `XshellzException` | Operation requires a `running` box (or UUID not found). |
| `MissingKeyException` | `XshellzException` | `getOrCreate` found the box but no private key. |
| `UnsupportedLanguageException` | `XshellzException` | `runCode` language outside `bash, node, php, python, ruby`. |
| `CommandTimeoutException` | `XshellzException` | `RunOptions.timeout` elapsed before the command exited. |
| `ApiException` | `XshellzException` | Any other 4xx/5xx; carries `statusCode()` and `body()`. |
