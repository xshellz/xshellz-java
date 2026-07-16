package com.xshellz.sandbox;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECPrivateKey;

/**
 * Bridges JDK-native Ed25519 keys to sshj. Internal.
 *
 * <p>sshj's ssh-ed25519 implementation is backed by the {@code net.i2p.crypto:eddsa}
 * bridge library (a transitive sshj dependency): its signature engine and its
 * public-key wire encoding both require {@code net.i2p.crypto.eddsa.EdDSA*Key}
 * objects and reject JDK {@code EdEC*} keys. The JDK-generated keypair is
 * therefore rebuilt from its raw seed / raw public point into i2p key objects
 * before it is handed to sshj. {@code SshjCompatibilityTest} proves the full
 * round-trip: the generated key signs through sshj's own ssh-ed25519 signature
 * factory, the signature verifies under the JDK's Ed25519 implementation, and
 * the public blob sshj sends on the wire is byte-identical to the OpenSSH line
 * registered with the control plane.
 */
final class SshjKeys {

    private SshjKeys() {
    }

    /** Wraps a JDK ed25519 {@link KeyPair} as an sshj {@link KeyProvider}. */
    static KeyProvider keyProvider(KeyPair keyPair) {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        byte[] seed = ((EdECPrivateKey) keyPair.getPrivate()).getBytes()
                .orElseThrow(() -> new XshellzException(
                        "The Ed25519 private key does not expose its raw bytes."));
        byte[] publicRaw = SshKeys.rawPublicKey(keyPair.getPublic());
        PrivateKey privateKey = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, spec));
        PublicKey publicKey = new EdDSAPublicKey(new EdDSAPublicKeySpec(publicRaw, spec));

        return new KeyProvider() {
            @Override
            public PrivateKey getPrivate() {
                return privateKey;
            }

            @Override
            public PublicKey getPublic() {
                return publicKey;
            }

            @Override
            public KeyType getType() {
                return KeyType.ED25519;
            }
        };
    }
}
