package com.xshellz.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local keystore: save/load/delete, sanitization, permissions. No network. */
class KeystoreTest {

    private static final String KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n";

    @Test
    void saveThenLoadRoundTrips(@TempDir Path dir) {
        Keystore keystore = Keystore.at(dir);
        keystore.save("my-box", KEY);
        assertEquals(KEY, keystore.load("my-box"));
        assertEquals(dir.resolve("my-box.key"), keystore.pathFor("my-box"));
    }

    @Test
    void loadReturnsNullWhenMissing(@TempDir Path dir) {
        assertNull(Keystore.at(dir).load("nope"));
    }

    @Test
    void saveCreatesTheDirectoryAndRestrictsPermissions(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("keys");
        Keystore keystore = Keystore.at(nested);
        keystore.save("box", KEY);

        Path file = keystore.pathFor("box");
        assertTrue(Files.isRegularFile(file));
        try {
            assertEquals(perms("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(perms("rwx------"), Files.getPosixFilePermissions(nested));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem: perms are best-effort by design.
        }
    }

    private static Set<PosixFilePermission> perms(String spec) {
        return java.nio.file.attribute.PosixFilePermissions.fromString(spec);
    }

    @Test
    void deleteRemovesTheKeyAndReportsWhetherItExisted(@TempDir Path dir) {
        Keystore keystore = Keystore.at(dir);
        keystore.save("box", KEY);
        assertTrue(keystore.delete("box"));
        assertNull(keystore.load("box"));
        assertFalse(keystore.delete("box"));
    }

    @Test
    void sanitizeMapsUnsafeCharactersToUnderscores() {
        assertEquals("my-box_1.2", Keystore.sanitize("my-box_1.2"));
        assertEquals("a_b_c", Keystore.sanitize("a b/c"));
        assertEquals("_", Keystore.sanitize(""));
        assertEquals("..__", Keystore.sanitize("../é"));
    }

    @Test
    void sanitizedNamesCannotEscapeTheDirectory(@TempDir Path dir) {
        Keystore keystore = Keystore.at(dir);
        keystore.save("../evil", KEY);
        assertTrue(keystore.pathFor("../evil").normalize().startsWith(dir));
        assertEquals(KEY, keystore.load("../evil"));
    }

    @Test
    void defaultDirectoryIsUnderUserHome() {
        Path expected = Path.of(System.getProperty("user.home"), ".xshellz", "keys");
        assertEquals(expected, Keystore.defaultDirectory());
        assertEquals(expected, Keystore.defaultKeystore().directory());
    }

    @Test
    void atRejectsNull() {
        assertThrows(XshellzException.class, () -> Keystore.at(null));
    }
}
