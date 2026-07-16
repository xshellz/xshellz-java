package com.xshellz.sandbox;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.FileSystemFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Real SSH data plane (sshj): exec over a session channel, files over SFTP.
 * Internal.
 *
 * <p>Host keys are auto-accepted: sandbox host keys are generated at spawn
 * time, so there is no out-of-band fingerprint to verify against.
 */
final class SshjTransport implements SandboxTransport {

    private static final String USERNAME = "root";
    private static final int CONNECT_TIMEOUT_MS = 30_000;

    private final SSHClient ssh;
    private SFTPClient sftp;

    SshjTransport(String host, int port, KeyPair keyPair) {
        ssh = new SSHClient();
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
        try {
            ssh.connect(host, port);
            ssh.authPublickey(USERNAME, SshjKeys.keyProvider(keyPair));
        } catch (IOException e) {
            close();
            throw new XshellzException(
                    "SSH connection to " + USERNAME + "@" + host + ":" + port + " failed: " + e, e);
        }
    }

    @Override
    public CommandResult exec(String command, Duration timeout, Consumer<String> onStdout, Consumer<String> onStderr) {
        try (Session session = ssh.startSession()) {
            Session.Command cmd = session.exec(command);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outPump = pump(cmd.getInputStream(), stdout, onStdout);
            Thread errPump = pump(cmd.getErrorStream(), stderr, onStderr);

            try {
                if (timeout != null) {
                    cmd.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } else {
                    cmd.join();
                }
            } catch (ConnectionException e) {
                if (timeout != null && isTimeout(e)) {
                    throw new CommandTimeoutException(
                            "Command did not finish within " + timeout.toSeconds() + " seconds: "
                                    + command, e);
                }
                throw new XshellzException("SSH exec failed: " + e, e);
            }

            outPump.join();
            errPump.join();
            Integer exitStatus = cmd.getExitStatus();
            return new CommandResult(
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8),
                    exitStatus == null ? -1 : exitStatus);
        } catch (IOException e) {
            throw new XshellzException("SSH exec failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XshellzException("Interrupted while waiting for the command.", e);
        }
    }

    private static boolean isTimeout(ConnectionException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return true;
            }
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timeout");
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream sink, Consumer<String> callback) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            int read;
            try {
                while ((read = in.read(buffer)) != -1) {
                    sink.write(buffer, 0, read);
                    if (callback != null) {
                        callback.accept(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // channel closed
            }
        }, "xshellz-ssh-pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Override
    public byte[] readFile(String path) {
        try {
            RemoteFile file = sftp().open(path);
            try (file; InputStream in = file.new RemoteFileInputStream()) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new XshellzException("SFTP read of " + path + " failed: " + e, e);
        }
    }

    @Override
    public void writeFile(String path, byte[] data) {
        try {
            RemoteFile file = sftp().open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC));
            try (file; OutputStream out = file.new RemoteFileOutputStream()) {
                out.write(data);
            }
        } catch (IOException e) {
            throw new XshellzException("SFTP write of " + path + " failed: " + e, e);
        }
    }

    @Override
    public void upload(Path localPath, String remotePath) {
        try {
            sftp().put(new FileSystemFile(localPath.toFile()), remotePath);
        } catch (IOException e) {
            throw new XshellzException("SFTP upload to " + remotePath + " failed: " + e, e);
        }
    }

    @Override
    public void download(String remotePath, Path localPath) {
        try {
            sftp().get(remotePath, new FileSystemFile(localPath.toFile()));
        } catch (IOException e) {
            throw new XshellzException("SFTP download of " + remotePath + " failed: " + e, e);
        }
    }

    private synchronized SFTPClient sftp() throws IOException {
        if (sftp == null) {
            sftp = ssh.newSFTPClient();
        }
        return sftp;
    }

    @Override
    public void close() {
        try {
            if (sftp != null) {
                sftp.close();
                sftp = null;
            }
        } catch (IOException ignored) {
            // best effort
        }
        try {
            ssh.disconnect();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
