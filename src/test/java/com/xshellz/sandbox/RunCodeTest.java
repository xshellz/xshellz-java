package com.xshellz.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.xshellz.sandbox.TestSupport.shellPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Code interpreter: runCode writes a temp file, execs, always cleans up. */
class RunCodeTest {

    private static Sandbox makeSandbox(FakeTransport fake) {
        ApiClient api = new ApiClient("k", "http://127.0.0.1:1/v1", Duration.ofSeconds(1));
        return new Sandbox(SandboxInfo.fromJson(shellPayload()), api, null, null, fake);
    }

    @Test
    void runCodeWritesExecutesAndDeletesTheTempFile() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("42\n", "", 0);
        Sandbox sbx = makeSandbox(fake);

        CommandResult result = sbx.runCode("python", "print(6*7)");

        assertEquals("42\n", result.stdout());
        assertEquals(1, fake.files.size());
        String path = fake.files.keySet().iterator().next();
        assertTrue(path.startsWith("/tmp/xshellz-code-") && path.endsWith(".py"), path);
        assertEquals("print(6*7)", new String(fake.files.get(path), StandardCharsets.UTF_8));
        assertEquals("python3 " + path, fake.commands.get(0));
        assertEquals("rm -f " + path, fake.commands.get(1));
    }

    @Test
    void runCodeMapsEveryLanguageToItsInterpreterAndExtension() {
        String[][] expectations = {
                {"python", "python3", ".py"},
                {"node", "node", ".js"},
                {"bash", "bash", ".sh"},
                {"ruby", "ruby", ".rb"},
                {"php", "php", ".php"},
        };
        for (String[] expectation : expectations) {
            FakeTransport fake = new FakeTransport();
            makeSandbox(fake).runCode(expectation[0], "code");
            String exec = fake.commands.get(0);
            assertTrue(exec.startsWith(expectation[1] + " /tmp/xshellz-code-"), exec);
            assertTrue(exec.endsWith(expectation[2]), exec);
        }
    }

    @Test
    void runCodeIsCaseInsensitiveOnTheLanguage() {
        FakeTransport fake = new FakeTransport();
        makeSandbox(fake).runCode("Python", "x = 1");
        assertTrue(fake.commands.get(0).startsWith("python3 "), fake.commands.get(0));
    }

    @Test
    void runCodeDeletesTheTempFileEvenWhenTheSnippetFails() {
        FakeTransport fake = new FakeTransport();
        fake.nextResult = new CommandResult("", "SyntaxError", 1);
        Sandbox sbx = makeSandbox(fake);

        CommandResult result = sbx.runCode("python", "oops(");

        assertEquals(1, result.exitCode());
        assertTrue(fake.commands.get(1).startsWith("rm -f /tmp/xshellz-code-"),
                fake.commands.get(1));
    }

    @Test
    void runCodeHonoursRunOptions() {
        FakeTransport fake = new FakeTransport();
        Sandbox sbx = makeSandbox(fake);
        sbx.runCode("bash", "pwd", RunOptions.builder().cwd("/srv").build());
        assertTrue(fake.commands.get(0).startsWith("cd /srv && bash /tmp/xshellz-code-"),
                fake.commands.get(0));
    }

    @Test
    void unknownLanguageThrowsListingTheSupportedOnes() {
        Sandbox sbx = makeSandbox(new FakeTransport());
        UnsupportedLanguageException e = assertThrows(UnsupportedLanguageException.class,
                () -> sbx.runCode("perl", "print 42"));
        assertTrue(e.getMessage().contains("bash, node, php, python, ruby"));
        assertThrows(UnsupportedLanguageException.class, () -> sbx.runCode(null, "x"));
    }
}
