package com.xshellz.sandbox;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.xshellz.sandbox.TestSupport.CANONICAL_UUID;
import static com.xshellz.sandbox.TestSupport.MAPPER;
import static com.xshellz.sandbox.TestSupport.shellPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sandbox.getOrCreate against the control-plane stub + a @TempDir keystore. */
class GetOrCreateTest {

    private static GetOrCreateOptions.Builder options(TestSupport.ControlPlaneStub stub, Path keys) {
        GetOrCreateOptions.Builder builder = GetOrCreateOptions.builder()
                .apiKey("test-key")
                .apiUrl(stub.baseUrl())
                .timeout(Duration.ofSeconds(5));
        return keys == null ? builder.disableKeystore() : builder.keystore(Keystore.at(keys));
    }

    private static String emptyList() {
        return "[]";
    }

    private static String listWith(String name, String status) {
        ArrayNode list = MAPPER.createArrayNode();
        list.add(shellPayload(p -> {
            p.put("name", name);
            p.put("status", status);
        }));
        return list.toString();
    }

    @Test
    void createsWhenNotFoundAndPersistsTheKey(@TempDir Path keys) throws Exception {
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(recorded -> {
            if ("GET".equals(recorded.method())) {
                return new TestSupport.Response(200, emptyList());
            }
            return new TestSupport.Response(200, shellPayload(p -> p.put("name", "perm")).toString());
        })) {
            Sandbox sbx = Sandbox.getOrCreate("perm", options(stub, keys).build());

            assertEquals(CANONICAL_UUID, sbx.uuid());
            assertEquals(List.of("GET /v1/shells/agent", "POST /v1/shells/agent"), stub.calls());
            TestSupport.Recorded post = stub.requests.get(1);
            var body = MAPPER.readTree(post.body());
            assertEquals("perm", body.path("name").asText());
            assertTrue(body.path("ssh_public_key").asText().startsWith("ssh-ed25519 "));

            String stored = Keystore.at(keys).load("perm");
            assertNotNull(stored);
            assertEquals(sbx.privateKeyOpenSsh(), stored);

            // getOrCreate boxes are permanent: close() must NOT destroy them.
            sbx.close();
            assertEquals(2, stub.requests.size());
        }
    }

    @Test
    void attachesToExistingBoxWithTheStoredKey(@TempDir Path keys) throws Exception {
        String key = SshKeys.generate().privateKeyOpenSsh();
        Keystore.at(keys).save("perm", key);
        try (TestSupport.ControlPlaneStub stub =
                TestSupport.ControlPlaneStub.start(200, listWith("perm", "running"))) {
            Sandbox sbx = Sandbox.getOrCreate("perm", options(stub, keys).build());

            assertEquals("running", sbx.status());
            assertEquals(key, sbx.privateKeyOpenSsh());
            assertEquals(List.of("GET /v1/shells/agent"), stub.calls());
        }
    }

    @Test
    void startsAStoppedBoxBeforeReturning(@TempDir Path keys) throws Exception {
        Keystore.at(keys).save("perm", SshKeys.generate().privateKeyOpenSsh());
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(recorded -> {
            if ("GET".equals(recorded.method())) {
                return new TestSupport.Response(200, listWith("perm", "stopped"));
            }
            return new TestSupport.Response(200, shellPayload(p -> p.put("name", "perm")).toString());
        })) {
            Sandbox sbx = Sandbox.getOrCreate("perm", options(stub, keys).build());

            assertEquals("running", sbx.status());
            assertEquals(List.of(
                    "GET /v1/shells/agent",
                    "POST /v1/shells/agent/" + CANONICAL_UUID + "/start"), stub.calls());
        }
    }

    @Test
    void explicitPrivateKeyWinsOverTheKeystore(@TempDir Path keys) throws Exception {
        Keystore.at(keys).save("perm", SshKeys.generate().privateKeyOpenSsh());
        String explicit = SshKeys.generate().privateKeyOpenSsh();
        try (TestSupport.ControlPlaneStub stub =
                TestSupport.ControlPlaneStub.start(200, listWith("perm", "running"))) {
            Sandbox sbx = Sandbox.getOrCreate("perm",
                    options(stub, keys).privateKey(explicit).build());
            assertEquals(explicit, sbx.privateKeyOpenSsh());
        }
    }

    @Test
    void missingKeyThrowsAndSaysWhereItLooked(@TempDir Path keys) throws Exception {
        try (TestSupport.ControlPlaneStub stub =
                TestSupport.ControlPlaneStub.start(200, listWith("perm", "running"))) {
            MissingKeyException e = assertThrows(MissingKeyException.class,
                    () -> Sandbox.getOrCreate("perm", options(stub, keys).build()));
            assertTrue(e.getMessage().contains(keys.resolve("perm.key").toString()));
        }
    }

    @Test
    void disabledKeystoreMakesExistingBoxesRequireAnExplicitKey() throws Exception {
        try (TestSupport.ControlPlaneStub stub =
                TestSupport.ControlPlaneStub.start(200, listWith("perm", "running"))) {
            MissingKeyException e = assertThrows(MissingKeyException.class,
                    () -> Sandbox.getOrCreate("perm", options(stub, null).build()));
            assertTrue(e.getMessage().contains("keystore is disabled"));
        }
    }

    @Test
    void disabledKeystoreStillCreatesButPersistsNothing(@TempDir Path home) throws Exception {
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(recorded -> {
            if ("GET".equals(recorded.method())) {
                return new TestSupport.Response(200, emptyList());
            }
            return new TestSupport.Response(200, shellPayload(p -> p.put("name", "perm")).toString());
        })) {
            Sandbox sbx = Sandbox.getOrCreate("perm", options(stub, null).build());
            assertNotNull(sbx.privateKeyOpenSsh());
        }
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(XshellzException.class,
                () -> Sandbox.getOrCreate(" ", GetOrCreateOptions.builder().apiKey("k").build()));
        assertThrows(XshellzException.class,
                () -> Sandbox.getOrCreate(null, GetOrCreateOptions.builder().apiKey("k").build()));
    }

    @Test
    void keystoreBuilderRejectsNull() {
        assertThrows(XshellzException.class, () -> GetOrCreateOptions.builder().keystore(null));
    }
}
