package com.xshellz.sandbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.xshellz.sandbox.TestSupport.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Account boxfile template: GET/PUT /v1/shells/agent/boxfile. */
class BoxfileTest {

    private static ApiOptions options(TestSupport.ControlPlaneStub stub) {
        return ApiOptions.builder()
                .apiKey("test-key")
                .apiUrl(stub.baseUrl())
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void getBoxfileReturnsTheSavedManifest() throws Exception {
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(
                200, "{\"manifest\": \"apt jq\\npip requests\"}")) {
            assertEquals("apt jq\npip requests", Sandbox.getBoxfile(options(stub)));
            assertEquals(List.of("GET /v1/shells/agent/boxfile"), stub.calls());
        }
    }

    @Test
    void getBoxfileReturnsNullWhenNoneIsSaved() throws Exception {
        try (TestSupport.ControlPlaneStub stub =
                TestSupport.ControlPlaneStub.start(200, "{\"manifest\": null}")) {
            assertNull(Sandbox.getBoxfile(options(stub)));
        }
    }

    @Test
    void setBoxfilePutsTheManifestAndReturnsItAsStored() throws Exception {
        try (TestSupport.ControlPlaneStub stub =
                TestSupport.ControlPlaneStub.start(200, "{\"manifest\": \"apt ripgrep\"}")) {
            assertEquals("apt ripgrep", Sandbox.setBoxfile("apt ripgrep", options(stub)));

            assertEquals(List.of("PUT /v1/shells/agent/boxfile"), stub.calls());
            TestSupport.Recorded put = stub.requests.get(0);
            assertEquals("apt ripgrep", MAPPER.readTree(put.body()).path("manifest").asText());
            assertTrue(put.authorization().equals("Bearer test-key"));
        }
    }

    @Test
    void setBoxfileWithNullClearsTheManifest() throws Exception {
        try (TestSupport.ControlPlaneStub stub =
                TestSupport.ControlPlaneStub.start(200, "{\"manifest\": null}")) {
            assertNull(Sandbox.setBoxfile(null, options(stub)));
            assertTrue(MAPPER.readTree(stub.requests.get(0).body()).path("manifest").isNull());
        }
    }
}
