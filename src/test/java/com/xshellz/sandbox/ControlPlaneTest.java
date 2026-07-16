package com.xshellz.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static com.xshellz.sandbox.TestSupport.CANONICAL_UUID;
import static com.xshellz.sandbox.TestSupport.MAPPER;
import static com.xshellz.sandbox.TestSupport.shellPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Control-plane tests against a local {@code com.sun.net.httpserver.HttpServer}. No real network. */
class ControlPlaneTest {

    private static final Pattern PUBLIC_KEY_RE =
            Pattern.compile("^ssh-ed25519 [A-Za-z0-9+/=]+( .*)?$");

    private static CreateOptions.Builder options(TestSupport.ControlPlaneStub stub) {
        return CreateOptions.builder().apiKey("pat-123").apiUrl(stub.baseUrl())
                .timeout(Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ //
    // create()
    // ------------------------------------------------------------------ //

    @Test
    void createPostsGeneratedPublicKeyAndReturnsRunningSandbox() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(
                200, shellPayload(p -> p.put("name", "my-box")).toString())) {

            Sandbox sbx = Sandbox.create(options(stub).name("my-box").build());

            TestSupport.Recorded request = stub.requests.get(0);
            assertEquals("POST", request.method());
            assertEquals("/v1/shells/agent", request.path());
            assertEquals("Bearer pat-123", request.authorization());

            JsonNode body = MAPPER.readTree(request.body());
            assertEquals("my-box", body.path("name").asText());
            assertTrue(PUBLIC_KEY_RE.matcher(body.path("ssh_public_key").asText()).matches());

            assertEquals(CANONICAL_UUID, sbx.uuid());
            assertEquals("running", sbx.status());
            assertEquals("shellus1.xshellz.com", sbx.sshHost());
            assertEquals(42001, sbx.sshPort());
            assertEquals("ssh -p 42001 root@shellus1.xshellz.com", sbx.sshCommand());
            assertEquals("my-box", sbx.name());
            assertTrue(sbx.info().gvisor());
            assertNotNull(sbx.privateKeyOpenSsh());
            assertTrue(sbx.privateKeyOpenSsh().contains("OPENSSH PRIVATE KEY"));
        }
    }

    @Test
    void createOmitsNameWhenNotGiven() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(200, shellPayload().toString())) {
            Sandbox.create(options(stub).build());

            JsonNode body = MAPPER.readTree(stub.requests.get(0).body());
            assertFalse(body.has("name"));
            assertTrue(body.has("ssh_public_key"));
        }
    }

    // ------------------------------------------------------------------ //
    // Error mapping (guard chain -> typed exceptions)
    // ------------------------------------------------------------------ //

    private static XshellzException createFailingWith(int status, String json) throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(status, json)) {
            return assertThrows(XshellzException.class,
                    () -> Sandbox.create(options(stub).build()));
        }
    }

    @Test
    void status401MapsToAuthException() throws Exception {
        XshellzException e = createFailingWith(401, "{\"message\": \"Unauthenticated.\"}");
        assertTrue(e instanceof AuthException);
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void status403QuotaLimitMapsToQuotaException() throws Exception {
        XshellzException e = createFailingWith(403,
                "{\"message\": \"You've reached your plan's agent shell limit (1).\"}");
        assertTrue(e instanceof QuotaException);
        assertTrue(e.getMessage().contains("agent shell limit"));
    }

    @Test
    void status403EntitlementMapsToQuotaException() throws Exception {
        XshellzException e = createFailingWith(403,
                "{\"message\": \"Your plan does not include agent shells. Upgrade to add one.\"}");
        assertTrue(e instanceof QuotaException);
        assertTrue(e.getMessage().contains("does not include agent shells"));
    }

    @Test
    void status403VerificationRequiredMapsToAuthException() throws Exception {
        XshellzException e = createFailingWith(403,
                "{\"error\": \"verification_required\", \"message\": \"Verify your account with "
                        + "a card (free - nothing is charged) to create a shell.\"}");
        assertTrue(e instanceof AuthException);
        assertTrue(e.getMessage().contains("verification"));
    }

    @Test
    void status403LimitedPreviewMapsToAuthException() throws Exception {
        XshellzException e = createFailingWith(403,
                "{\"message\": \"Agent Shell is in limited preview.\"}");
        assertTrue(e instanceof AuthException);
        assertTrue(e.getMessage().contains("limited preview"));
    }

    @Test
    void status429MapsToApiExceptionWithStatus() throws Exception {
        XshellzException e = createFailingWith(429, "{\"message\": \"Too Many Attempts.\"}");
        assertTrue(e instanceof ApiException);
        assertEquals(429, ((ApiException) e).statusCode());
    }

    @Test
    void status503MapsToApiExceptionWithBody() throws Exception {
        XshellzException e = createFailingWith(503,
                "{\"message\": \"Agent shells are not available yet.\"}");
        ApiException api = (ApiException) e;
        assertEquals(503, api.statusCode());
        assertEquals("Agent shells are not available yet.",
                ((JsonNode) api.body()).path("message").asText());
    }

    // ------------------------------------------------------------------ //
    // list() / connect()
    // ------------------------------------------------------------------ //

    @Test
    void listParsesTheBareTopLevelArray() throws Exception {
        ArrayNode payload = MAPPER.createArrayNode();
        payload.add(shellPayload());
        payload.add(shellPayload(p -> {
            p.put("uuid", "deadbeef");
            p.put("name", "second");
            p.put("status", "stopped");
        }));

        try (var stub = TestSupport.ControlPlaneStub.start(200, payload.toString())) {
            List<SandboxInfo> infos = Sandbox.list(
                    ApiOptions.builder().apiKey("k").apiUrl(stub.baseUrl()).build());

            assertEquals("GET /v1/shells/agent", stub.calls().get(0));
            assertEquals(2, infos.size());
            assertEquals(CANONICAL_UUID, infos.get(0).uuid());
            assertTrue(infos.get(0).gvisor());
            assertEquals("deadbeef", infos.get(1).uuid());
            assertEquals("stopped", infos.get(1).status());
        }
    }

    @Test
    void connectFindsTheSandboxInTheList() throws Exception {
        String privateKey = SshKeys.generate().privateKeyOpenSsh();
        ArrayNode payload = MAPPER.createArrayNode();
        payload.add(shellPayload());

        try (var stub = TestSupport.ControlPlaneStub.start(200, payload.toString())) {
            Sandbox sbx = Sandbox.connect(CANONICAL_UUID, privateKey,
                    ApiOptions.builder().apiKey("k").apiUrl(stub.baseUrl()).build());

            assertEquals("GET /v1/shells/agent", stub.calls().get(0));
            assertEquals(CANONICAL_UUID, sbx.uuid());
            assertEquals("running", sbx.status());
            assertEquals(privateKey, sbx.privateKeyOpenSsh());
        }
    }

    @Test
    void connectUnknownUuidThrowsSandboxNotRunning() throws Exception {
        String privateKey = SshKeys.generate().privateKeyOpenSsh();

        try (var stub = TestSupport.ControlPlaneStub.start(200, "[]")) {
            SandboxNotRunningException e = assertThrows(SandboxNotRunningException.class,
                    () -> Sandbox.connect("no-such-uuid", privateKey,
                            ApiOptions.builder().apiKey("k").apiUrl(stub.baseUrl()).build()));
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    // ------------------------------------------------------------------ //
    // kill() / start() / close()
    // ------------------------------------------------------------------ //

    private static Sandbox sandboxAgainst(TestSupport.ControlPlaneStub stub) {
        return new Sandbox(SandboxInfo.fromJson(shellPayload()), stub.apiClient(), null, null, null);
    }

    @Test
    void killIssuesDeleteAndIsIdempotent() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(200, "{\"deleted\": true}")) {
            Sandbox sbx = sandboxAgainst(stub);
            sbx.kill();
            sbx.kill(); // second call is a no-op

            assertEquals(List.of("DELETE /v1/shells/agent/" + CANONICAL_UUID), stub.calls());
        }
    }

    @Test
    void killSwallows404AlreadyGone() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(
                404, "{\"message\": \"Agent shell not found.\"}")) {
            sandboxAgainst(stub).kill(); // must not throw
        }
    }

    @Test
    void startPostsAndUpdatesInfo() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(
                200, shellPayload(p -> p.put("status", "running")).toString())) {
            Sandbox sbx = sandboxAgainst(stub);
            sbx.start();

            assertEquals(List.of("POST /v1/shells/agent/" + CANONICAL_UUID + "/start"),
                    stub.calls());
            assertEquals("running", sbx.status());
        }
    }

    @Test
    void start404MapsToSandboxNotRunning() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(
                404, "{\"message\": \"Stopped agent shell not found.\"}")) {
            assertThrows(SandboxNotRunningException.class, () -> sandboxAgainst(stub).start());
        }
    }

    @Test
    void tryWithResourcesKillsTheSandbox() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(200, "{\"deleted\": true}")) {
            try (Sandbox sbx = sandboxAgainst(stub)) {
                assertEquals(CANONICAL_UUID, sbx.uuid());
            }
            assertEquals(List.of("DELETE /v1/shells/agent/" + CANONICAL_UUID), stub.calls());
        }
    }

    @Test
    void detachSkipsTheKillOnClose() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(200, "{\"deleted\": true}")) {
            try (Sandbox sbx = sandboxAgainst(stub)) {
                sbx.detach();
            }
            assertEquals(List.of(), stub.calls());
        }
    }

    @Test
    void refreshRefetchesState() throws Exception {
        try (var stub = TestSupport.ControlPlaneStub.start(recorded -> new TestSupport.Response(
                200, "[" + shellPayload(p -> p.put("status", "stopped")) + "]"))) {
            Sandbox sbx = sandboxAgainst(stub);
            SandboxInfo refreshed = sbx.refresh();

            assertEquals("stopped", refreshed.status());
            assertEquals("stopped", sbx.status());
        }
    }
}
