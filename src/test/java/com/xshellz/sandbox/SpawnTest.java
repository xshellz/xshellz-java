package com.xshellz.sandbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.xshellz.sandbox.TestSupport.shellPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Background jobs: spawn / JobHandle / jobs() against the fake transport. */
class SpawnTest {

    private static Sandbox makeSandbox(FakeTransport fake) {
        ApiClient api = new ApiClient("k", "http://127.0.0.1:1/v1", Duration.ofSeconds(1));
        return new Sandbox(SandboxInfo.fromJson(shellPayload()), api, null, null, fake);
    }

    @Test
    void spawnBuildsTheNohupCommandAndCapturesThePid() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("4242\n", "", 0);
        Sandbox sbx = makeSandbox(fake);

        JobHandle job = sbx.spawn("sleep 60 && echo done", "worker");

        assertEquals(4242L, job.pid());
        assertTrue(job.id().startsWith("worker-"), job.id());
        assertEquals("$HOME/.xshellz/jobs/" + job.id() + ".log", job.logPath());

        String command = fake.commands.get(0);
        assertTrue(command.startsWith("mkdir -p $HOME/.xshellz/jobs && nohup bash -c "), command);
        assertTrue(command.contains("'sleep 60 && echo done'"), command);
        assertTrue(command.contains("> $HOME/.xshellz/jobs/" + job.id() + ".log 2>&1 < /dev/null"),
                command);
        assertTrue(command.endsWith("& echo $! | tee $HOME/.xshellz/jobs/" + job.id() + ".pid"),
                command);
    }

    @Test
    void spawnWithoutANameUsesTheJobPrefixAndSanitizesGivenNames() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("7\n", "", 0);
        Sandbox sbx = makeSandbox(fake);

        assertTrue(sbx.spawn("true").id().startsWith("job-"));
        assertTrue(sbx.spawn("true", "my worker/1").id().startsWith("my_worker_1-"));
    }

    @Test
    void spawnEscapesSingleQuotesInTheCommand() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("9\n", "", 0);
        makeSandbox(fake).spawn("echo 'hi there'");
        assertTrue(fake.commands.get(0).contains("'echo '\\''hi there'\\'''"), fake.commands.get(0));
    }

    @Test
    void spawnThrowsWhenNoPidComesBack() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("", "bash: not found", 127);
        XshellzException e = assertThrows(XshellzException.class,
                () -> makeSandbox(fake).spawn("true"));
        assertTrue(e.getMessage().contains("no PID captured"));
        assertTrue(e.getMessage().contains("bash: not found"));
    }

    @Test
    void isRunningProbesWithKillZero() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("55\n", "", 0);
        Sandbox sbx = makeSandbox(fake);
        JobHandle job = sbx.spawn("true");

        fake.nextResult = new CommandResult("", "", 0);
        assertTrue(job.isRunning());
        assertEquals("kill -0 55 2>/dev/null", fake.commands.get(1));

        fake.nextResult = new CommandResult("", "", 1);
        assertFalse(job.isRunning());
    }

    @Test
    void logsTailsTheLogFile() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("77\n", "", 0);
        Sandbox sbx = makeSandbox(fake);
        JobHandle job = sbx.spawn("true", "w");

        fake.nextResult = new CommandResult("line1\nline2\n", "", 0);
        assertEquals("line1\nline2\n", job.logs());
        assertEquals("tail -n 100 " + job.logPath() + " 2>/dev/null", fake.commands.get(1));

        job.logs(7);
        assertEquals("tail -n 7 " + job.logPath() + " 2>/dev/null", fake.commands.get(2));
    }

    @Test
    void stopSendsTermThenKillAfterTheGrace() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("88\n", "", 0);
        Sandbox sbx = makeSandbox(fake);
        JobHandle job = sbx.spawn("true");

        job.stop();
        String command = fake.commands.get(1);
        assertTrue(command.startsWith("kill -TERM 88 2>/dev/null || true"), command);
        assertTrue(command.contains("kill -0 88 2>/dev/null || exit 0"), command);
        assertTrue(command.endsWith("kill -KILL 88 2>/dev/null || true"), command);
    }

    @Test
    void jobsListsPidFilesWithLiveness() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("worker-abc123 42 running\njob-def456 43 dead\n", "", 0);
        Sandbox sbx = makeSandbox(fake);

        List<JobInfo> jobs = sbx.jobs();

        assertEquals(2, jobs.size());
        assertEquals(new JobInfo("worker-abc123", 42, true, "$HOME/.xshellz/jobs/worker-abc123.log"),
                jobs.get(0));
        assertEquals(new JobInfo("job-def456", 43, false, "$HOME/.xshellz/jobs/job-def456.log"),
                jobs.get(1));
        assertTrue(fake.commands.get(0).contains("for f in *.pid"), fake.commands.get(0));
    }

    @Test
    void jobsReturnsEmptyOnNoJobsDirectoryOrGarbageOutput() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("", "", 0);
        assertEquals(List.of(), makeSandbox(fake).jobs());

        fake.nextResult = new CommandResult("not a job line\nx y\n", "", 0);
        assertEquals(List.of(), makeSandbox(fake).jobs());

        fake.nextResult = new CommandResult("id notanumber running\n", "", 0);
        assertEquals(List.of(), makeSandbox(fake).jobs());
    }
}
