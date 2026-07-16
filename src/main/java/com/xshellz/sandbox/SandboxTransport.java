package com.xshellz.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Minimal data-plane surface the {@link Sandbox} needs, behind an interface so
 * the exec/file layer is unit-testable and so v1 can swap SSH for the sidecar
 * HTTP transport without touching the public API. Internal.
 */
interface SandboxTransport extends AutoCloseable {

    CommandResult exec(String command, Duration timeout, Consumer<String> onStdout, Consumer<String> onStderr);

    byte[] readFile(String path);

    void writeFile(String path, byte[] data);

    void upload(Path localPath, String remotePath);

    void download(String remotePath, Path localPath);

    @Override
    void close();
}
