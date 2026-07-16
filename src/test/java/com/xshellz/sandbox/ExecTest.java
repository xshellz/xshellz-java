package com.xshellz.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.xshellz.sandbox.TestSupport.CANONICAL_UUID;
import static com.xshellz.sandbox.TestSupport.shellPayload;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data-plane tests: Sandbox exec/files against a fake SSH transport. No network. */
class ExecTest {

    private static Sandbox makeSandbox(FakeTransport fake, String status) {
        ApiClient api = new ApiClient("k", "http://127.0.0.1:1/v1", Duration.ofSeconds(1));
        return new Sandbox(
                SandboxInfo.fromJson(shellPayload(p -> p.put("status", status))),
                api, null, null, fake);
    }

    private static Sandbox makeSandbox(FakeTransport fake) {
        return makeSandbox(fake, "running");
    }

    @Test
    void runReturnsCommandResultAndNonZeroExitDoesNotThrow() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("", "boom", 2);
        Sandbox sbx = makeSandbox(fake);

        CommandResult result = sbx.run("false");

        assertEquals(2, result.exitCode());
        assertEquals("boom", result.stderr());
        assertFalse(result.ok());
        assertEquals(List.of("false"), fake.commands);
    }

    @Test
    void runWrapsCwdAndEnv() {
        FakeTransport fake = new FakeTransport();
        Sandbox sbx = makeSandbox(fake);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("FOO", "a b");
        env.put("BAR", "1");
        sbx.run("make build", RunOptions.builder().cwd("/srv/app dir").env(env).build());

        assertEquals(List.of("export FOO='a b' BAR=1 && cd '/srv/app dir' && make build"),
                fake.commands);
    }

    @Test
    void runRejectsInvalidEnvNames() {
        Sandbox sbx = makeSandbox(new FakeTransport());
        XshellzException e = assertThrows(XshellzException.class,
                () -> sbx.run("true", RunOptions.builder().env(Map.of("BAD-NAME", "x")).build()));
        assertTrue(e.getMessage().contains("environment variable name"));
    }

    @Test
    void runStreamsToCallbacks() {
        FakeTransport fake = new FakeTransport();
        fake.streamChunks.add(new String[] {"stdout", "line1\n"});
        fake.streamChunks.add(new String[] {"stderr", "warn\n"});
        fake.streamChunks.add(new String[] {"stdout", "line2\n"});
        fake.nextResult = new CommandResult("line1\nline2\n", "warn\n", 0);
        Sandbox sbx = makeSandbox(fake);

        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();
        CommandResult result = sbx.run("build",
                RunOptions.builder().onStdout(out::add).onStderr(err::add).build());

        assertEquals(List.of("line1\n", "line2\n"), out);
        assertEquals(List.of("warn\n"), err);
        assertEquals("line1\nline2\n", result.stdout());
    }

    @Test
    void fileRoundTripAndUploadDownload(@TempDir Path tmp) throws Exception {
        FakeTransport fake = new FakeTransport();
        Sandbox sbx = makeSandbox(fake);

        byte[] binary = new byte[] {0, 1, 'd', 'a', 't', 'a'};
        sbx.writeFile("/tmp/a.bin", binary);
        assertArrayEquals(binary, sbx.readFile("/tmp/a.bin"));

        Path local = tmp.resolve("local.txt");
        Files.write(local, "hello".getBytes());
        sbx.upload(local, "/tmp/remote.txt");
        assertArrayEquals("hello".getBytes(), fake.files.get("/tmp/remote.txt"));

        Path out = tmp.resolve("out.txt");
        sbx.download("/tmp/remote.txt", out);
        assertArrayEquals("hello".getBytes(), Files.readAllBytes(out));
    }

    @Test
    void runOnNonRunningSandboxThrows() {
        Sandbox sbx = makeSandbox(null, "stopped");
        SandboxNotRunningException e = assertThrows(SandboxNotRunningException.class,
                () -> sbx.run("true"));
        assertTrue(e.getMessage().contains("stopped"));
        assertTrue(e.getMessage().contains("start()"));
    }

    @Test
    void runWithoutPrivateKeyThrows() {
        Sandbox sbx = makeSandbox(null, "running"); // no transport, no key
        XshellzException e = assertThrows(XshellzException.class, () -> sbx.run("true"));
        assertTrue(e.getMessage().contains("private key"));
    }

    @Test
    void closeKillsTheSandboxAndClosesTheTransport() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(200, "{\"deleted\": true}")) {
            FakeTransport fake = new FakeTransport();
            try (Sandbox sbx = new Sandbox(SandboxInfo.fromJson(shellPayload()),
                    stub.apiClient(), null, null, fake)) {
                sbx.run("true");
            }

            assertEquals(List.of("DELETE /v1/shells/agent/" + CANONICAL_UUID), stub.calls());
            assertTrue(fake.closed);
        }
    }

    @Test
    void detachSkipsTheKillButStillClosesSsh() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(200, "{\"deleted\": true}")) {
            FakeTransport fake = new FakeTransport();
            try (Sandbox sbx = new Sandbox(SandboxInfo.fromJson(shellPayload()),
                    stub.apiClient(), null, null, fake)) {
                sbx.detach();
            }

            assertEquals(List.of(), stub.calls());
            assertTrue(fake.closed); // SSH closed; box kept alive
        }
    }

    @Test
    void buildShellCommandPlain() {
        assertEquals("echo hi", ShellCommand.build("echo hi", null, null));
        assertEquals("cd /tmp && echo hi", ShellCommand.build("echo hi", "/tmp", null));
    }
}
