package com.xshellz.sandbox;

import com.hierynomus.sshj.key.KeyAlgorithm;
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.signature.Signature;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the riskiest detail of the SDK: that sshj can authenticate with the
 * in-memory ed25519 keypair we generate with the JDK.
 *
 * <p>SSH public-key auth is exactly "sign the session blob with the private
 * key; the server verifies against authorized_keys". So if (a) sshj's own
 * ssh-ed25519 signature factory signs with our key object and the signature
 * verifies against our public key, and (b) the public-key blob sshj sends on
 * the wire is byte-identical to the blob we register with the control plane,
 * then authentication works end to end.
 */
class SshjCompatibilityTest {

    private static byte[] ourWireBlob(SshKeys.Ed25519KeyPair keys) {
        return Base64.getDecoder().decode(keys.publicKeyLine().split(" ")[1]);
    }

    private static Signature sshjSignature() {
        // The exact path SSHClient uses during pubkey auth: pick the negotiated
        // key algorithm by name, then create its signature engine.
        KeyAlgorithm algorithm = Factory.Named.Util.create(
                new DefaultConfig().getKeyAlgorithms(), KeyType.ED25519.toString());
        assertNotNull(algorithm, "sshj has no ssh-ed25519 key algorithm factory");
        return algorithm.newSignature();
    }

    @Test
    void keyProviderReportsEd25519() throws Exception {
        KeyProvider provider = SshjKeys.keyProvider(SshKeys.generate().keyPair());
        assertEquals(KeyType.ED25519, provider.getType());
        assertEquals(KeyType.ED25519, KeyType.fromKey(provider.getPublic()));
    }

    @Test
    void sshjSignsWithOurGeneratedKeyAndJdkVerifies() throws Exception {
        SshKeys.Ed25519KeyPair keys = SshKeys.generate();
        KeyProvider provider = SshjKeys.keyProvider(keys.keyPair());

        byte[] data = "xshellz ssh auth probe".getBytes(StandardCharsets.UTF_8);
        Signature signer = sshjSignature();
        signer.initSign(provider.getPrivate());
        signer.update(data);
        byte[] rawSignature = signer.sign();
        assertEquals(64, rawSignature.length, "ed25519 signatures are 64 bytes");

        java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
        verifier.initVerify(keys.keyPair().getPublic());
        verifier.update(data);
        assertTrue(verifier.verify(rawSignature),
                "signature produced through sshj must verify against the JDK public key");
    }

    @Test
    void sshjEncodesOurPublicKeyBlobIdentically() throws Exception {
        SshKeys.Ed25519KeyPair keys = SshKeys.generate();
        KeyProvider provider = SshjKeys.keyProvider(keys.keyPair());

        byte[] sshjBlob = new Buffer.PlainBuffer().putPublicKey(provider.getPublic()).getCompactData();

        assertArrayEquals(ourWireBlob(keys), sshjBlob,
                "the public key sshj sends during auth must match authorized_keys");
    }

    @Test
    void sshjParsesOurOpenSshPrivateKeyPem() throws Exception {
        SshKeys.Ed25519KeyPair keys = SshKeys.generate();

        OpenSSHKeyV1KeyFile keyFile = new OpenSSHKeyV1KeyFile();
        keyFile.init(new StringReader(keys.privateKeyOpenSsh()));

        byte[] sshjBlob = new Buffer.PlainBuffer().putPublicKey(keyFile.getPublic()).getCompactData();
        assertArrayEquals(ourWireBlob(keys), sshjBlob);

        byte[] data = "reconnect probe".getBytes(StandardCharsets.UTF_8);
        Signature signer = sshjSignature();
        signer.initSign(keyFile.getPrivate());
        signer.update(data);
        java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
        verifier.initVerify(keys.keyPair().getPublic());
        verifier.update(data);
        assertTrue(verifier.verify(signer.sign()));
    }

    @Test
    void reloadedKeypairSignsThroughSshjToo() throws Exception {
        SshKeys.Ed25519KeyPair original = SshKeys.generate();
        SshKeys.Ed25519KeyPair reloaded = SshKeys.fromOpenSsh(original.privateKeyOpenSsh());
        KeyProvider provider = SshjKeys.keyProvider(reloaded.keyPair());

        byte[] data = "connect() probe".getBytes(StandardCharsets.UTF_8);
        Signature signer = sshjSignature();
        signer.initSign(provider.getPrivate());
        signer.update(data);

        java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
        verifier.initVerify(original.keyPair().getPublic());
        verifier.update(data);
        assertTrue(verifier.verify(signer.sign()));
    }
}
