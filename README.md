# xshellz

[![CI](https://github.com/xshellz/xshellz-java/actions/workflows/ci.yml/badge.svg)](https://github.com/xshellz/xshellz-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.xshellz/xshellz)](https://central.sonatype.com/artifact/com.xshellz/xshellz)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

The official Java SDK for [xShellz](https://xshellz.com) sandboxes - spawn a
real Linux box from your program and run anything in it, in three lines.

**What is a sandbox?** A sandbox is a small, disposable Linux computer that
lives on xShellz's servers, isolated from everything else (including your own
machine) by [gVisor](https://gvisor.dev). Your program creates one, runs
commands or untrusted/AI-generated code inside it, copies files in and out,
and throws it away - nothing that happens inside can touch anything outside.

Requires Java 17+.

## Quickstart

1. **Install** - add the dependency:

   ```xml
   <dependency>
     <groupId>com.xshellz</groupId>
     <artifactId>xshellz</artifactId>
     <version>0.2.0</version>
   </dependency>
   ```

   Gradle: `implementation("com.xshellz:xshellz:0.2.0")`

2. **Get an API key** - sign up at [app.xshellz.com](https://app.xshellz.com),
   create a personal access token with `read` and `write` scopes
   (Settings -> API tokens, or `POST /v1/auth/tokens`), then:

   ```bash
   export XSHELLZ_API_KEY="your-token"
   ```

3. **Hello world:**

   ```java
   import com.xshellz.sandbox.Sandbox;

   try (Sandbox sbx = Sandbox.create()) {
       System.out.println(sbx.run("echo hello from $(hostname)").stdout());
   } // the box is destroyed when the block exits
   ```

Spawning is synchronous - `Sandbox.create()` returns once the box is running,
typically in a few seconds. Each box is a real root shell with a package
manager and network access.

## Recipes

### Run a command

```java
try (Sandbox sbx = Sandbox.create()) {
    CommandResult r = sbx.run("apt-get update && apt-get install -y jq",
            RunOptions.builder().timeout(Duration.ofMinutes(5)).build());
    System.out.println(r.exitCode() + " " + r.stdout());

    // A non-zero exit code does NOT throw - it's data:
    assert sbx.run("false").exitCode() == 1;

    // cwd, env, and live output streaming:
    sbx.run("make test", RunOptions.builder()
            .cwd("/srv/app")
            .env(Map.of("CI", "1"))
            .onStdout(System.out::print)
            .build());
}
```

### A permanent named box that survives restarts

`Sandbox.getOrCreate(name)` attaches to the box with that exact name, creating
it the first time. The box's SSH private key is saved to `~/.xshellz/keys/` so
the *next* run of your program (or the next day) finds it again. If the box was
idle-stopped in the meantime, it is started automatically.

```java
Sandbox sbx = Sandbox.getOrCreate("my-project");
sbx.run("echo 'state that persists' >> /root/notes.txt");
sbx.close(); // a getOrCreate box is NOT destroyed on close - it's permanent

// ...tomorrow, in a different process:
Sandbox same = Sandbox.getOrCreate("my-project"); // same box, same /root
same.kill(); // destroy it explicitly when you're truly done
```

Security note: the key is plaintext on disk (permissions `0600`); delete the
file to revoke that machine's access. Custom directory or no disk at all:

```java
Sandbox.getOrCreate("ci-box", GetOrCreateOptions.builder()
        .keystore(Keystore.at(Path.of("/secure/keys")))   // custom location
        .build());
Sandbox.getOrCreate("ci-box", GetOrCreateOptions.builder()
        .disableKeystore().privateKey(keyFromYourVault)   // bring your own key
        .build());
```

### Background jobs (spawn)

`spawn` starts a command with `nohup` and returns immediately - the job keeps
running even after you disconnect or your program exits.

```java
try (Sandbox sbx = Sandbox.create()) {
    JobHandle job = sbx.spawn("python3 -m http.server 8000", "webserver");

    job.isRunning();   // true
    job.logs();        // last 100 log lines (logs(500) for more)
    job.pid();         // the process id inside the box
    sbx.jobs();        // list every job and whether it's still alive
    job.stop();        // SIGTERM, then SIGKILL after 5s
}
```

### Run AI-generated code safely (runCode)

Don't paste model output into a shell string - `runCode` writes the snippet to
a temp file inside the box, runs the right interpreter, and cleans up. No
quoting pitfalls, and the code can't touch your machine.

```java
String generated = llm.complete("write python that prints the 10th fibonacci number");
CommandResult r = sbx.runCode("python", generated);
System.out.println(r.ok() ? r.stdout() : r.stderr());
```

Supported languages: `python` (python3), `node`, `bash`, `ruby`, `php`
(the interpreter must be installed in the box; python3 and bash always are).

### Files up and down

```java
sbx.writeFile("/tmp/config.json", "{\"debug\": true}".getBytes());
byte[] data = sbx.readFile("/tmp/config.json");
sbx.upload(Path.of("local.txt"), "/tmp/remote.txt");
sbx.download("/tmp/remote.txt", Path.of("out.txt"));
```

### Check resource usage

```java
SandboxStats stats = sbx.stats();
System.out.printf("mem %d/%d MB, cpu %.1f%%, disk %d/%d MB%n",
        stats.memUsedMb(), stats.memAllowedMb(), stats.cpuPercent(),
        stats.diskUsedMb(), stats.diskAllowedMb());

SandboxProcs procs = sbx.procs(); // top processes + active SSH sessions
```

### Open a web terminal

```java
String url = sbx.terminalUrl(); // signed URL, valid ~1 hour - mint fresh, don't store
System.out.println("Open in a browser: " + url);
```

### Provision every new box the same way (boxfile)

The account-level boxfile is a template applied when a NEW box is created -
use it to preinstall your dependencies:

```java
Sandbox.setBoxfile("apt ripgrep jq\npip requests");
Sandbox.getBoxfile();  // read it back
Sandbox.setBoxfile(null); // clear it
```

## API reference

Every public class, method, parameter, return shape, and error is documented
in **[docs/API.md](docs/API.md)**.

## Configuration

| Environment variable | Meaning | Default |
|---|---|---|
| `XSHELLZ_API_KEY` | Personal access token (`read` + `write` scopes) | - (required) |
| `XSHELLZ_API_URL` | Control-plane base URL | `https://api.xshellz.com/v1` |

Precedence: explicit option (`.apiKey(...)`, `.apiUrl(...)`) > environment
variable > default.

## Errors

All SDK exceptions are unchecked and extend `XshellzException`:

| Exception | When |
|---|---|
| `XshellzException` | Base class; also network/JSON-level failures |
| `AuthException` | 401/403: missing or bad token, scopes, account gates |
| `QuotaException` | Plan sandbox limit reached / no sandbox entitlement |
| `SandboxNotRunningException` | Operation needs a `running` box |
| `MissingKeyException` | `getOrCreate` found the box but no private key for it |
| `UnsupportedLanguageException` | `runCode` language not supported |
| `CommandTimeoutException` | `RunOptions.timeout(...)` exceeded |
| `ApiException` | Any other 4xx/5xx (carries `statusCode()` + `body()`) |

## v0 limits

- **Free tier: 1 concurrent sandbox.** A second `Sandbox.create()` throws
  `QuotaException` - attach to the existing box (`Sandbox.list()` +
  `Sandbox.connect()`, or `getOrCreate`) or `kill()` it first. Paid plans
  raise the limit.
- **Free boxes idle-stop after ~30 minutes.** `/home` and your key survive;
  `sbx.start()` (or `getOrCreate`) resumes the same box.
- Sandbox creation is throttled to 10 requests/minute per account.

## How it works

- **Control plane**: HTTPS to `api.xshellz.com/v1` (create / list / start /
  destroy / stats), authenticated by your token. Built on the JDK's
  `java.net.http` client.
- **Data plane**: SSH directly to the box as `root` (via
  [sshj](https://github.com/hierynomus/sshj)). `Sandbox.create()` generates an
  in-memory ed25519 keypair per sandbox; the private key never leaves your
  process (unless you opt into the `getOrCreate` keystore) and the server only
  ever sees the public half.
- **Host keys are auto-accepted.** Sandbox host keys are minted at spawn time,
  so there is no out-of-band fingerprint to pin. If your threat model requires
  host-key verification, connect manually using `sbx.sshCommand()`.

## Local development (Docker)

No local JDK or Maven needed - build, tests, and the coverage gate all run
inside Docker (a named volume caches `~/.m2` so re-runs are fast):

```bash
docker compose run --rm test   # mvn -B verify: tests + javadoc + coverage >= 80%
```

## License

[MIT](LICENSE)
