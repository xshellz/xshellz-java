package com.xshellz.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** In-memory stand-in for the SSH data plane. */
final class FakeTransport implements SandboxTransport {

    final List<String> commands = new ArrayList<>();
    final Map<String, byte[]> files = new HashMap<>();
    boolean closed;
    CommandResult nextResult = new CommandResult("out", "", 0);
    /** When non-empty, each exec consumes one result from here instead. */
    final Deque<CommandResult> resultQueue = new ArrayDeque<>();
    final List<String[]> streamChunks = new ArrayList<>(); // {stream, chunk}

    @Override
    public CommandResult exec(String command, Duration timeout, Consumer<String> onStdout,
            Consumer<String> onStderr) {
        commands.add(command);
        if (!resultQueue.isEmpty()) {
            return resultQueue.poll();
        }
        for (String[] chunk : streamChunks) {
            if ("stdout".equals(chunk[0]) && onStdout != null) {
                onStdout.accept(chunk[1]);
            }
            if ("stderr".equals(chunk[0]) && onStderr != null) {
                onStderr.accept(chunk[1]);
            }
        }
        return nextResult;
    }

    @Override
    public byte[] readFile(String path) {
        return files.get(path);
    }

    @Override
    public void writeFile(String path, byte[] data) {
        files.put(path, data);
    }

    @Override
    public void upload(Path localPath, String remotePath) {
        try {
            files.put(remotePath, Files.readAllBytes(localPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void download(String remotePath, Path localPath) {
        try {
            Files.write(localPath, files.get(remotePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
