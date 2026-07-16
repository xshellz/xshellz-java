package com.xshellz.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshKeysTest {

    private static final Pattern PUBLIC_KEY_RE =
            Pattern.compile("^ssh-ed25519 [A-Za-z0-9+/]+={0,2} xshellz-sdk$");

    @Test
    void publicKeyLineIsValidOpenSshFormat() {
        SshKeys.Ed25519KeyPair keys = SshKeys.generate();

        assertTrue(PUBLIC_KEY_RE.matcher(keys.publicKeyLine()).matches(), keys.publicKeyLine());

        byte[] blob = Base64.getDecoder().decode(keys.publicKeyLine().split(" ")[1]);
        String blobText = new String(blob, StandardCharsets.ISO_8859_1);
        assertTrue(blobText.contains("ssh-ed25519"));
    }

    @Test
    void publicKeyMatchesServerSideValidationRegex() {
        // The control plane validates against this exact regex (CreateAgentShellRequest).
        Pattern serverRegex = Pattern.compile(
                "^(ssh-ed25519|ssh-rsa|ecdsa-sha2-[a-z0-9-]+|sk-ssh-ed25519@openssh\\.com"
                        + "|sk-ecdsa-sha2-[a-z0-9-]+@openssh\\.com)\\s+[A-Za-z0-9+/=]+(\\s+.*)?$");
        assertTrue(serverRegex.matcher(SshKeys.generate().publicKeyLine()).matches());
    }

    @Test
    void privateKeyIsOpenSshPem() {
        SshKeys.Ed25519KeyPair keys = SshKeys.generate();
        assertTrue(keys.privateKeyOpenSsh().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"));
        assertTrue(keys.privateKeyOpenSsh().trim().endsWith("-----END OPENSSH PRIVATE KEY-----"));
    }

    @Test
    void privateKeyRoundTripsThroughOpenSshEncoding() {
        SshKeys.Ed25519KeyPair original = SshKeys.generate();

        SshKeys.Ed25519KeyPair reloaded = SshKeys.fromOpenSsh(original.privateKeyOpenSsh());

        assertArrayEquals(original.publicKeyRaw(), reloaded.publicKeyRaw());
        assertEquals(original.publicKeyLine(), reloaded.publicKeyLine());
        assertArrayEquals(
                original.keyPair().getPrivate().getEncoded(),
                reloaded.keyPair().getPrivate().getEncoded());
        assertArrayEquals(
                original.keyPair().getPublic().getEncoded(),
                reloaded.keyPair().getPublic().getEncoded());
    }

    @Test
    void eachKeypairIsUnique() {
        assertNotEquals(SshKeys.generate().publicKeyLine(), SshKeys.generate().publicKeyLine());
    }

    @Test
    void loadingGarbageThrowsHelpfulError() {
        XshellzException e = assertThrows(XshellzException.class,
                () -> SshKeys.fromOpenSsh("not a key at all"));
        assertTrue(e.getMessage().contains("Could not load the private key"));
    }

    @Test
    void loadingTruncatedPemThrows() {
        String pem = SshKeys.generate().privateKeyOpenSsh();
        String truncated = pem.substring(0, 120) + "\n-----END OPENSSH PRIVATE KEY-----\n";
        assertThrows(XshellzException.class, () -> SshKeys.fromOpenSsh(truncated));
    }
}
