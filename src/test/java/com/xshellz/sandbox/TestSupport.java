package com.xshellz.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Shared helpers: canonical wire payloads and a local control-plane stub. */
final class TestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String CANONICAL_UUID = "11111111-2222-3333-4444-555555555555";

    private TestSupport() {
    }

    /** A canonical AgentShellResponse wire payload (snake_case). */
    static ObjectNode shellPayload(Consumer<ObjectNode> overrides) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("uuid", CANONICAL_UUID);
        payload.put("name", "agent-shell");
        payload.put("status", "running");
        payload.put("ssh_command", "ssh -p 42001 root@shellus1.xshellz.com");
        payload.put("ssh_host", "shellus1.xshellz.com");
        payload.put("ssh_port", 42001);
        payload.put("web_terminal_ready", true);
        payload.putNull("trial_ends_at");
        payload.put("always_on", true);
        payload.put("trial_hours_remaining", 0.0);
        payload.put("spawned_at", "2026-07-16T12:00:00+00:00");
        payload.put("created_at", "2026-07-16T12:00:00+00:00");
        payload.put("isolation", "runsc");
        payload.put("gvisor", true);
        if (overrides != null) {
            overrides.accept(payload);
        }
        return payload;
    }

    static ObjectNode shellPayload() {
        return shellPayload(null);
    }

    record Recorded(String method, String path, String authorization, String body) {
    }

    /** A tiny in-process control plane backed by com.sun.net.httpserver.HttpServer. */
    static final class ControlPlaneStub implements AutoCloseable {

        private final HttpServer server;
        final List<Recorded> requests = new CopyOnWriteArrayList<>();

        private ControlPlaneStub(HttpServer server) {
            this.server = server;
        }

        /** Starts a stub whose handler decides the response per recorded request. */
        static ControlPlaneStub start(Responder responder) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ControlPlaneStub stub = new ControlPlaneStub(server);
            HttpHandler handler = exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Recorded recorded = new Recorded(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        body);
                stub.requests.add(recorded);
                Response response = responder.respond(recorded);
                byte[] bytes = response.json().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            };
            server.createContext("/", handler);
            server.start();
            return stub;
        }

        /** Starts a stub that answers every request identically. */
        static ControlPlaneStub start(int status, String json) throws IOException {
            return start(recorded -> new Response(status, json));
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        ApiClient apiClient() {
            return new ApiClient("test-key", baseUrl(), Duration.ofSeconds(5));
        }

        List<String> calls() {
            return requests.stream().map(r -> r.method() + " " + r.path()).toList();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    record Response(int status, String json) {
    }

    @FunctionalInterface
    interface Responder {
        Response respond(Recorded recorded);
    }
}
