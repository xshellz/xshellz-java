package com.xshellz.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.xshellz.sandbox.TestSupport.CANONICAL_UUID;
import static com.xshellz.sandbox.TestSupport.shellPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Introspection + lifecycle wrappers: stats / procs / restart / terminalUrl. */
class IntrospectionTest {

    private static final String STATS_JSON = """
            {"mem_used_mb": 137, "mem_limit_mb": 1024, "mem_allowed_mb": 1024,
             "cpu_percent": 12.5, "cpu_allowed_vcpus": 1.0, "cpu_throttled_periods": 3,
             "pids_current": 17, "pids_allowed": 256,
             "disk_used_mb": 420, "disk_allowed_mb": 5120,
             "net_rx_mb": 12, "net_tx_mb": 7, "blk_read_mb": 90, "blk_write_mb": 33}""";

    private static final String PROCS_JSON = """
            {"procs": [{"pid": 1, "comm": "bash", "cpu": 0.5, "mem": 1.2},
                       {"pid": 42, "comm": "claude", "cpu": 88.0, "mem": 20.5}],
             "sessions": 2, "agents": ["claude"],
             "disk_used_mb": 420, "disk_allowed_mb": 5120}""";

    private static Sandbox makeSandbox(TestSupport.ControlPlaneStub stub) {
        return new Sandbox(SandboxInfo.fromJson(shellPayload()), stub.apiClient(), null, null, null);
    }

    @Test
    void statsMirrorsTheWireFields() throws Exception {
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(200, STATS_JSON)) {
            SandboxStats stats = makeSandbox(stub).stats();

            assertEquals(List.of("GET /v1/shells/agent/" + CANONICAL_UUID + "/stats"), stub.calls());
            assertEquals(137, stats.memUsedMb());
            assertEquals(1024, stats.memLimitMb());
            assertEquals(1024, stats.memAllowedMb());
            assertEquals(12.5, stats.cpuPercent());
            assertEquals(1.0, stats.cpuAllowedVcpus());
            assertEquals(3, stats.cpuThrottledPeriods());
            assertEquals(17, stats.pidsCurrent());
            assertEquals(256, stats.pidsAllowed());
            assertEquals(420, stats.diskUsedMb());
            assertEquals(5120, stats.diskAllowedMb());
            assertEquals(12, stats.netRxMb());
            assertEquals(7, stats.netTxMb());
            assertEquals(90, stats.blkReadMb());
            assertEquals(33, stats.blkWriteMb());
        }
    }

    @Test
    void procsParsesProcessesSessionsAgentsAndDisk() throws Exception {
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(200, PROCS_JSON)) {
            SandboxProcs procs = makeSandbox(stub).procs();

            assertEquals(List.of("GET /v1/shells/agent/" + CANONICAL_UUID + "/procs"), stub.calls());
            assertEquals(2, procs.procs().size());
            assertEquals(new SandboxProcs.ProcessInfo(42, "claude", 88.0, 20.5), procs.procs().get(1));
            assertEquals(2, procs.sessions());
            assertEquals(List.of("claude"), procs.agents());
            assertEquals(420, procs.diskUsedMb());
            assertEquals(5120, procs.diskAllowedMb());
        }
    }

    @Test
    void procsToleratesAnEmptyPayload() throws Exception {
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(200, "{}")) {
            SandboxProcs procs = makeSandbox(stub).procs();
            assertEquals(List.of(), procs.procs());
            assertEquals(List.of(), procs.agents());
            assertEquals(0, procs.sessions());
        }
    }

    @Test
    void restartPostsAndRefreshesTheInfo() throws Exception {
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(
                200, shellPayload(p -> p.put("ssh_port", 42099)).toString())) {
            Sandbox sbx = makeSandbox(stub);
            sbx.restart();

            assertEquals(List.of("POST /v1/shells/agent/" + CANONICAL_UUID + "/restart"),
                    stub.calls());
            assertEquals(42099, sbx.sshPort());
        }
    }

    @Test
    void terminalUrlReturnsTheFreshSignedUrl() throws Exception {
        String url = "https://shellus1.xshellz.com:8443/t/" + CANONICAL_UUID + "/1752700000.abcd/";
        try (TestSupport.ControlPlaneStub stub = TestSupport.ControlPlaneStub.start(
                200, "{\"url\": \"" + url + "\"}")) {
            assertEquals(url, makeSandbox(stub).terminalUrl());
            assertTrue(stub.calls().contains("GET /v1/shells/agent/" + CANONICAL_UUID + "/terminal"));
        }
    }
}
