# xshellz

Official Java SDK for [xShellz](https://xshellz.com) sandboxes: throwaway,
gVisor-isolated Linux boxes you can spawn and run commands in from your own
program - in three lines.

```xml
<dependency>
  <groupId>com.xshellz</groupId>
  <artifactId>xshellz</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
import com.xshellz.sandbox.CommandResult;
import com.xshellz.sandbox.Sandbox;

try (Sandbox sbx = Sandbox.create()) {
    CommandResult r = sbx.run("python3 -c 'print(6*7)'");
    System.out.println(r.stdout()); // 42
} // the box is destroyed when the block exits
```

Each sandbox is a real Linux box (root shell, package manager, network) running
under [gVisor](https://gvisor.dev) kernel isolation. Spawning is synchronous -
`Sandbox.create()` returns once the box is running, typically in a few seconds.

Requires Java 17+.

## Authentication

The SDK authenticates with an xShellz personal access token (PAT) carrying the
`read` and `write` scopes:

1. Create a token from your [xShellz dashboard](https://app.xshellz.com)
   (Settings -> API tokens), or via the API: `POST /v1/auth/tokens`.
2. Export it:

```bash
export XSHELLZ_API_KEY="your-token"
```

or pass it explicitly:
`Sandbox.create(CreateOptions.builder().apiKey("your-token").build())`.

Config precedence: explicit option > `XSHELLZ_API_KEY` / `XSHELLZ_API_URL`
environment variables > default (`https://api.xshellz.com/v1`).

To target a staging or self-hosted control plane:

```bash
export XSHELLZ_API_URL="https://api.staging.example.com/v1"
```

## Usage

### Run commands

```java
import com.xshellz.sandbox.*;

import java.time.Duration;
import java.util.Map;

try (Sandbox sbx = Sandbox.create(CreateOptions.builder().name("build-box").build())) {
    CommandResult r = sbx.run("apt-get update && apt-get install -y jq",
            RunOptions.builder().timeout(Duration.ofMinutes(5)).build());
    System.out.println(r.exitCode() + " " + r.stdout() + " " + r.stderr());

    // A non-zero exit code does NOT throw - it's data:
    r = sbx.run("false");
    assert r.exitCode() == 1;

    // cwd and env:
    sbx.run("make test", RunOptions.builder().cwd("/srv/app").env(Map.of("CI", "1")).build());

    // Stream long-running output as it arrives:
    sbx.run("npm run build", RunOptions.builder()
            .onStdout(System.out::print)
            .onStderr(System.err::print)
            .build());
}
```

### Files (SFTP)

```java
sbx.writeFile("/tmp/config.json", "{\"debug\": true}".getBytes());
byte[] data = sbx.readFile("/tmp/config.json");

sbx.upload(Path.of("local.txt"), "/tmp/remote.txt");
sbx.download("/tmp/remote.txt", Path.of("out.txt"));
```

### Lifecycle

```java
sbx.uuid();       // sandbox id
sbx.sshHost();    // e.g. "shellus1.xshellz.com"
sbx.sshPort();    // e.g. 42001
sbx.sshCommand(); // ready-to-copy "ssh -p 42001 root@..."
sbx.status();     // "running", "stopped", ...

sbx.detach();     // keep the box alive after close()
sbx.kill();       // destroy the box explicitly
sbx.start();      // resume an idle-stopped box

// Re-attach later (persist sbx.privateKeyOpenSsh() + sbx.uuid() for this):
Sandbox again = Sandbox.connect(uuid, savedPrivateKey);

// Enumerate your sandboxes:
for (SandboxInfo info : Sandbox.list()) {
    System.out.println(info.uuid() + " " + info.status());
}
```

### Typed exceptions

```java
import com.xshellz.sandbox.*;

Sandbox sbx;
try {
    sbx = Sandbox.create();
} catch (QuotaException e) {
    // plan limit reached - attach to the existing box instead
    SandboxInfo existing = Sandbox.list().get(0);
    sbx = Sandbox.connect(existing.uuid(), savedPrivateKey);
} catch (AuthException e) {
    System.err.println(e.getMessage()); // missing/invalid token, scope, verification
}
```

- `XshellzException` - base class (unchecked) for everything the SDK throws
- `AuthException` - 401/403: bad or missing token, scopes, account gates
- `QuotaException` - plan sandbox limit reached / plan has no sandbox entitlement
- `SandboxNotRunningException` - operation needs a `running` box
- `CommandTimeoutException` - `RunOptions.timeout(...)` exceeded
- `ApiException` - any other 4xx/5xx (carries `statusCode()` and `body()`)

## How it works

- **Control plane**: HTTPS to `api.xshellz.com/v1` (create / list / start /
  destroy), authenticated by your PAT. Built on the JDK's `java.net.http`
  client - no HTTP dependency.
- **Data plane**: SSH directly to the box as `root` (via
  [sshj](https://github.com/hierynomus/sshj)). `Sandbox.create()` generates an
  in-memory ed25519 keypair per sandbox; the private key never leaves your
  process and the server never sees it - only the public half is installed in
  the box's `authorized_keys`.
- **Host keys are auto-accepted.** Sandbox host keys are generated at spawn
  time, so there is no out-of-band fingerprint to pin. If your threat model
  requires host-key verification, connect manually with your own SSH tooling
  using `sbx.sshCommand()`.

## v0 limits

- **Free tier: 1 concurrent sandbox.** A second `Sandbox.create()` throws
  `QuotaException` while one exists - use `Sandbox.list()` +
  `Sandbox.connect()` to attach to the existing box, or `kill()` it first.
  Paid plans raise the limit.
- **Free boxes idle-stop after ~30 minutes.** The box (its `/home` and your
  key) is preserved; call `sbx.start()` to resume it.
- Sandbox creation is throttled to 10 requests/minute per account.

## Local development (Docker)

No local JDK or Maven needed - the whole build and test cycle runs inside
Docker (a named volume caches `~/.m2` so re-runs are fast):

```bash
docker compose run --rm test
```

To run the full verify (tests + javadoc jar), the same as CI:

```bash
docker compose run --rm test mvn -B verify
```

## License

MIT
