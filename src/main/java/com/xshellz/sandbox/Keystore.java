package com.xshellz.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * A local directory of sandbox private keys, one file per sandbox name, used
 * by {@link Sandbox#getOrCreate(String)} to make named sandboxes survive
 * process restarts.
 *
 * <p>Default location: {@code ~/.xshellz/keys/} (resolved via the
 * {@code user.home} system property). Each key file is named after the
 * sanitized sandbox name with a {@code .key} suffix and contains the
 * OpenSSH-serialized ed25519 private key.
 *
 * <p><strong>Security note:</strong> keys are stored in plaintext on disk.
 * File permissions are restricted to {@code 0600} (and the directory to
 * {@code 0700}) on POSIX filesystems; on filesystems without POSIX permissions
 * (e.g. Windows) the files are only protected by the user profile's ACLs.
 * Deleting a key file revokes this machine's access to the box - the box
 * itself keeps running.
 */
public final class Keystore {

    private final Path directory;

    private Keystore(Path directory) {
        this.directory = directory;
    }

    /** The default keystore directory: {@code ~/.xshellz/keys/}. */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".xshellz", "keys");
    }

    /** A keystore rooted at {@link #defaultDirectory()}. */
    public static Keystore defaultKeystore() {
        return new Keystore(defaultDirectory());
    }

    /** A keystore rooted at a custom directory. */
    public static Keystore at(Path directory) {
        if (directory == null) {
            throw new XshellzException("Keystore.at(directory) requires a non-null directory.");
        }
        return new Keystore(directory);
    }

    /** The directory this keystore reads and writes. */
    public Path directory() {
        return directory;
    }

    /** The file where the key for {@code name} is (or would be) stored. */
    public Path pathFor(String name) {
        return directory.resolve(sanitize(name) + ".key");
    }

    /**
     * Persists the OpenSSH private key for {@code name}, creating the
     * directory if needed and restricting permissions to 0600 where supported.
     */
    public void save(String name, String privateKeyOpenSsh) {
        Path file = pathFor(name);
        try {
            Files.createDirectories(directory);
            restrict(directory, "rwx------");
            Files.writeString(file, privateKeyOpenSsh, StandardCharsets.UTF_8);
            restrict(file, "rw-------");
        } catch (IOException e) {
            throw new XshellzException("Could not save the private key to " + file + ": " + e, e);
        }
    }

    /** Loads the key for {@code name}, or returns {@code null} if none is stored. */
    public String load(String name) {
        Path file = pathFor(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new XshellzException("Could not read the private key at " + file + ": " + e, e);
        }
    }

    /** Deletes the stored key for {@code name}; returns whether a file was removed. */
    public boolean delete(String name) {
        try {
            return Files.deleteIfExists(pathFor(name));
        } catch (IOException e) {
            throw new XshellzException("Could not delete the private key for '" + name + "': " + e, e);
        }
    }

    /** Maps a sandbox name to a safe filename component. */
    static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "_" : cleaned;
    }

    /** Best-effort POSIX permission tightening (no-op where unsupported). */
    private static void restrict(Path path, String posixPerms) throws IOException {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString(posixPerms);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows): rely on the user profile ACL.
        }
    }
}
